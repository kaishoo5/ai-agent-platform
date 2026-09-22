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
                    createSrt(
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
                    "쇼츠 SRT 자동 생성 완료. fileId={}, language={}, subtitlePath={}, subtitleCount={}",
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

    private String createSrt(
            List<VideoTranscriptSegment> segments
    ) {
        StringBuilder srt =
                new StringBuilder();

        int subtitleIndex =
                1;

        for (VideoTranscriptSegment segment : segments) {
            if (segment == null) {
                continue;
            }

            String text =
                    normalizeText(
                            segment.text()
                    );

            if (text.isBlank()) {
                continue;
            }

            long startMillis =
                    Math.max(
                            0L,
                            segment.startMillis()
                    );

            long endMillis =
                    Math.max(
                            startMillis,
                            segment.endMillis()
                    );

            if (endMillis <= startMillis) {
                continue;
            }

            List<String> subtitleTexts =
                    splitSubtitleText(
                            text
                    );

            if (subtitleTexts.isEmpty()) {
                continue;
            }

            long durationMillis =
                    endMillis
                            - startMillis;

            long totalTextLength =
                    subtitleTexts.stream()
                            .mapToLong(String::length)
                            .sum();

            long currentStartMillis =
                    startMillis;

            for (
                    int index = 0;
                    index < subtitleTexts.size();
                    index++
            ) {
                String subtitleText =
                        subtitleTexts.get(
                                index
                        );

                long currentEndMillis;

                if (
                        index
                                == subtitleTexts.size() - 1
                ) {
                    currentEndMillis =
                            endMillis;
                } else {
                    long subtitleDurationMillis =
                            Math.round(
                                    durationMillis
                                            * (
                                            subtitleText.length()
                                                    / (double) totalTextLength
                                    )
                            );

                    currentEndMillis =
                            Math.min(
                                    endMillis,
                                    currentStartMillis
                                            + subtitleDurationMillis
                            );
                }

                if (
                        currentEndMillis
                                <= currentStartMillis
                ) {
                    continue;
                }

                srt.append(
                        subtitleIndex++
                );
                srt.append(
                        System.lineSeparator()
                );
                srt.append(
                        formatTimestamp(
                                currentStartMillis
                        )
                );
                srt.append(
                        " --> "
                );
                srt.append(
                        formatTimestamp(
                                currentEndMillis
                        )
                );
                srt.append(
                        System.lineSeparator()
                );
                srt.append(
                        subtitleText
                );
                srt.append(
                        System.lineSeparator()
                );
                srt.append(
                        System.lineSeparator()
                );

                currentStartMillis =
                        currentEndMillis;
            }
        }

        return srt.toString();
    }

    private List<String> splitSubtitleText(
            String text
    ) {
        final int maxLength =
                20;

        List<String> result =
                new ArrayList<>();

        String[] words =
                text.split(
                        "\\s+"
                );

        StringBuilder current =
                new StringBuilder();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }

            if (
                    current.isEmpty()
            ) {
                if (
                        word.length()
                                <= maxLength
                ) {
                    current.append(
                            word
                    );
                } else {
                    splitLongWord(
                            word,
                            maxLength,
                            result
                    );
                }

                continue;
            }

            int nextLength =
                    current.length()
                            + 1
                            + word.length();

            if (
                    nextLength
                            <= maxLength
            ) {
                current
                        .append(
                                " "
                        )
                        .append(
                                word
                        );

                continue;
            }

            result.add(
                    current.toString()
            );

            current.setLength(
                    0
            );

            if (
                    word.length()
                            <= maxLength
            ) {
                current.append(
                        word
                );
            } else {
                splitLongWord(
                        word,
                        maxLength,
                        result
                );
            }
        }

        if (
                !current.isEmpty()
        ) {
            result.add(
                    current.toString()
            );
        }

        return result;
    }

    private void splitLongWord(
            String word,
            int maxLength,
            List<String> result
    ) {
        int start =
                0;

        while (
                start
                        < word.length()
        ) {
            int end =
                    Math.min(
                            start
                                    + maxLength,
                            word.length()
                    );

            result.add(
                    word.substring(
                            start,
                            end
                    )
            );

            start =
                    end;
        }
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
                        baseName + "_shorts.srt"
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