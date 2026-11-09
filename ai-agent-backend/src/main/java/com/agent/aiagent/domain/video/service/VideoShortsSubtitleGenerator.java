package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.model.VideoTranscriptSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoShortsSubtitleGenerator {

    private final WhisperTranscriber whisperTranscriber;

    public Path generate(
            String fileId,
            Path videoPath,
            long shortsStartMillis,
            long shortsEndMillis
    ) {
        validate(
                fileId,
                videoPath,
                shortsStartMillis,
                shortsEndMillis
        );

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        Path audioPath =
                createAudioPath(
                        normalizedVideoPath
                );

        Path subtitlePath =
                createSubtitlePath(
                        normalizedVideoPath
                );

        try {
            extractShortsAudio(
                    normalizedVideoPath,
                    audioPath,
                    shortsStartMillis,
                    shortsEndMillis
            );

            VideoTranscript transcript =
                    whisperTranscriber.transcribe(
                            fileId,
                            audioPath
                    );

            if (
                    transcript == null
                            || transcript.segments() == null
                            || transcript.segments().isEmpty()
            ) {
                log.info(
                        "쇼츠 자막 생성을 생략합니다. fileId={}, reason=noTranscript",
                        fileId
                );

                return null;
            }

            String srt =
                    createAss(
                            transcript.segments()
                    );

            if (srt.isBlank()) {
                log.info(
                        "쇼츠 자막 생성을 생략합니다. fileId={}, reason=noValidTranscript",
                        fileId
                );

                return null;
            }

            Files.writeString(
                    subtitlePath,
                    srt,
                    StandardCharsets.UTF_8
            );

            log.info(
                    "쇼츠 ASS 자동 생성 완료. fileId={}, language={}, subtitlePath={}, subtitleCount={}",
                    fileId,
                    transcript.language(),
                    subtitlePath,
                    transcript.segments().size()
            );

            return subtitlePath;
        } catch (IOException exception) {
            deleteIfExists(
                    subtitlePath
            );

            throw new IllegalStateException(
                    "쇼츠 자막 파일 생성 중 오류가 발생했습니다.",
                    exception
            );
        } finally {
            deleteIfExists(
                    audioPath
            );
        }
    }

    public void cleanup(
            Path subtitlePath
    ) {
        deleteIfExists(
                subtitlePath
        );
    }

    private void extractShortsAudio(
            Path videoPath,
            Path audioPath,
            long shortsStartMillis,
            long shortsEndMillis
    ) {
        double startSeconds =
                shortsStartMillis
                        / 1000.0;

        double durationSeconds =
                (
                        shortsEndMillis
                                - shortsStartMillis
                ) / 1000.0;

        List<String> command =
                new ArrayList<>();

        command.add(
                "ffmpeg"
        );

        command.add(
                "-y"
        );

        command.add(
                "-ss"
        );

        command.add(
                String.valueOf(
                        startSeconds
                )
        );

        command.add(
                "-i"
        );

        command.add(
                videoPath.toString()
        );

        command.add(
                "-t"
        );

        command.add(
                String.valueOf(
                        durationSeconds
                )
        );

        command.add(
                "-vn"
        );

        command.add(
                "-ac"
        );

        command.add(
                "1"
        );

        command.add(
                "-ar"
        );

        command.add(
                "16000"
        );

        command.add(
                "-c:a"
        );

        command.add(
                "pcm_s16le"
        );

        command.add(
                audioPath.toString()
        );

        log.info(
                "쇼츠 자막용 오디오 추출 시작. videoPath={}, startMillis={}, endMillis={}, audioPath={}",
                videoPath,
                shortsStartMillis,
                shortsEndMillis,
                audioPath
        );

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        command
                );

        processBuilder.redirectErrorStream(
                true
        );

        try {
            Process process =
                    processBuilder.start();

            String output =
                    readProcessOutput(
                            process
                    );

            int exitCode =
                    process.waitFor();

            if (exitCode != 0) {
                deleteIfExists(
                        audioPath
                );

                throw new IllegalStateException(
                        "쇼츠 자막용 오디오 추출에 실패했습니다."
                                + System.lineSeparator()
                                + output
                );
            }

            if (!Files.exists(audioPath)) {
                throw new IllegalStateException(
                        "FFmpeg 실행은 완료됐지만 쇼츠 자막용 오디오 파일이 생성되지 않았습니다."
                );
            }

            log.info(
                    "쇼츠 자막용 오디오 추출 완료. audioPath={}, size={}",
                    audioPath,
                    Files.size(
                            audioPath
                    )
            );
        } catch (IOException exception) {
            deleteIfExists(
                    audioPath
            );

            throw new IllegalStateException(
                    "쇼츠 자막용 FFmpeg 실행 중 오류가 발생했습니다.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            deleteIfExists(
                    audioPath
            );

            throw new IllegalStateException(
                    "쇼츠 자막용 오디오 추출이 중단되었습니다.",
                    exception
            );
        }
    }

    /**
     * 쇼츠 전용 ASS 자막. 한 화면에 최대 두 줄을 보여주며,
     * 마지막 어절만 포인트 컬러로 강조한다.
     * 강조 대상은 의미 분석 결과가 아니라 시각적 리듬을 위한 규칙이다.
     */
    private String createAss(List<VideoTranscriptSegment> segments) {
        StringBuilder ass = new StringBuilder("""
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 1080
                PlayResY: 1920
                WrapStyle: 2
                ScaledBorderAndShadow: yes

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Shorts,Malgun Gothic,94,&H00FFFFFF,&H00FFFFFF,&H9A101010,&H00000000,-1,0,0,0,100,100,0,0,1,2.5,0,2,85,85,440,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                """);

        for (VideoTranscriptSegment segment : segments) {
            if (segment == null) {
                continue;
            }
            String text = normalizeText(segment.text());
            long start = Math.max(0L, segment.startMillis());
            long end = Math.max(start, segment.endMillis());
            if (text.isBlank() || end <= start) {
                continue;
            }
            List<String> captions = splitSubtitleText(text);
            if (captions.isEmpty()) {
                continue;
            }
            long duration = end - start;
            long totalLength = captions.stream().mapToLong(String::length).sum();
            long current = start;
            for (int i = 0; i < captions.size(); i++) {
                String caption = captions.get(i);
                long next = i == captions.size() - 1 ? end :
                        Math.min(end, current + Math.round(duration * caption.length() / (double) totalLength));
                if (next <= current) {
                    continue;
                }
                ass.append("Dialogue: 0,")
                        .append(formatAssTimestamp(current)).append(',')
                        .append(formatAssTimestamp(next))
                        .append(",Shorts,,0,0,0,,")
                        .append(styleCaption(caption))
                        .append('\n');
                current = next;
            }
        }
        return ass.toString();
    }

    private String formatAssTimestamp(long millis) {
        long centiseconds = millis / 10;
        long hours = centiseconds / 360000;
        long minutes = (centiseconds / 6000) % 60;
        long seconds = (centiseconds / 100) % 60;
        long cs = centiseconds % 100;
        return "%d:%02d:%02d.%02d".formatted(hours, minutes, seconds, cs);
    }

    private String styleCaption(String caption) {
        // ASS 제어 문자는 음성 인식 결과에서 이스케이프한다.
        String clean = caption.replace("{", "(").replace("}", ")").replace("\\", "");
        String[] words = clean.split("\\s+");
        if (words.length == 0) {
            return clean;
        }
        int split = -1;
        int length = 0;
        for (int i = 0; i < words.length; i++) {
            if (length + words[i].length() + (i > 0 ? 1 : 0) > 9 && i > 0) {
                split = i;
                break;
            }
            length += words[i].length() + (i > 0 ? 1 : 0);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(i == split ? "\\N" : " ");
            }
            if (i == words.length - 1 && words[i].length() >= 2) {
                result.append("{\\c&H00D8FF&}").append(words[i]).append("{\\c&HFFFFFF&}");
            } else {
                result.append(words[i]);
            }
        }
        return result.toString();
    }

    private List<String> splitSubtitleText(String text) {
        final int maxLength = 18;
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            if (!current.isEmpty() && current.length() + word.length() + 1 > maxLength) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private Path createAudioPath(
            Path videoPath
    ) {
        String baseName =
                getBaseName(
                        videoPath
                );

        return videoPath
                .getParent()
                .resolve(
                        baseName + "_shorts_subtitle.wav"
                )
                .normalize();
    }

    private Path createSubtitlePath(
            Path videoPath
    ) {
        String baseName =
                getBaseName(
                        videoPath
                );

        return videoPath
                .getParent()
                .resolve(
                        baseName + "_shorts.ass"
                )
                .normalize();
    }

    private String getBaseName(
            Path videoPath
    ) {
        String fileName =
                videoPath
                        .getFileName()
                        .toString();

        int extensionIndex =
                fileName.lastIndexOf(
                        "."
                );

        return extensionIndex > 0
                ? fileName.substring(
                0,
                extensionIndex
        )
                : fileName;
    }

    private String normalizeText(
            String text
    ) {
        if (text == null) {
            return "";
        }

        return text
                .replace(
                        "\r\n",
                        " "
                )
                .replace(
                        "\n",
                        " "
                )
                .replace(
                        "\r",
                        " "
                )
                .trim();
    }

    private String formatTimestamp(
            long millis
    ) {
        long normalizedMillis =
                Math.max(
                        0L,
                        millis
                );

        long hours =
                normalizedMillis
                        / 3_600_000L;

        long minutes =
                (
                        normalizedMillis
                                % 3_600_000L
                ) / 60_000L;

        long seconds =
                (
                        normalizedMillis
                                % 60_000L
                ) / 1_000L;

        long milliseconds =
                normalizedMillis
                        % 1_000L;

        return String.format(
                "%02d:%02d:%02d,%03d",
                hours,
                minutes,
                seconds,
                milliseconds
        );
    }

    private String readProcessOutput(
            Process process
    ) throws IOException {
        StringBuilder builder =
                new StringBuilder();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {
            String line;

            while (
                    (
                            line =
                                    reader.readLine()
                    ) != null
            ) {
                builder
                        .append(
                                line
                        )
                        .append(
                                System.lineSeparator()
                        );
            }
        }

        return builder.toString();
    }

    private void validate(
            String fileId,
            Path videoPath,
            long shortsStartMillis,
            long shortsEndMillis
    ) {
        if (
                fileId == null
                        || fileId.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "fileId가 없습니다."
            );
        }

        if (videoPath == null) {
            throw new IllegalArgumentException(
                    "영상 파일 경로가 없습니다."
            );
        }

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(normalizedVideoPath)) {
            throw new IllegalArgumentException(
                    "영상 파일을 찾을 수 없습니다: "
                            + normalizedVideoPath
            );
        }

        if (!Files.isRegularFile(normalizedVideoPath)) {
            throw new IllegalArgumentException(
                    "유효한 영상 파일이 아닙니다: "
                            + normalizedVideoPath
            );
        }

        if (
                shortsStartMillis < 0
                        || shortsEndMillis <= shortsStartMillis
        ) {
            throw new IllegalArgumentException(
                    "유효하지 않은 쇼츠 자막 구간입니다."
            );
        }
    }

    private void deleteIfExists(
            Path path
    ) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(
                    path
            );
        } catch (IOException exception) {
            log.warn(
                    "쇼츠 자막 임시 파일 삭제 실패. path={}",
                    path,
                    exception
            );
        }
    }
}