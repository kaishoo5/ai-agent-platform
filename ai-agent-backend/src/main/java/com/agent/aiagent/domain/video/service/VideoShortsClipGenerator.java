package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
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
public class VideoShortsClipGenerator {

    private final VideoShortsTitleRenderer videoShortsTitleRenderer;

    public Path generate(
            Path videoPath,
            VideoHighlightSegment segment,
            Path subtitlePath,
            String originalFileName
    ) {
        return generate(
                videoPath,
                segment,
                subtitlePath,
                originalFileName,
                null
        );
    }

    public Path generate(
            Path videoPath,
            VideoHighlightSegment segment,
            Path subtitlePath,
            String originalFileName,
            String title
    ) {
        validate(videoPath, segment);

        Path normalizedVideoPath = videoPath.toAbsolutePath().normalize();
        Path normalizedSubtitlePath = normalizeSubtitlePath(subtitlePath);
        Path outputPath = createOutputPath(normalizedVideoPath, originalFileName);

        double startSeconds = segment.startMillis() / 1000.0;
        double durationSeconds =
                (segment.endMillis() - segment.startMillis()) / 1000.0;

        Path titleImagePath = null;

        try {
            if (title != null && !title.isBlank()) {
                titleImagePath = videoShortsTitleRenderer.render(
                        title,
                        normalizedVideoPath.getParent()
                );
            }

            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-ss");
            command.add(String.valueOf(startSeconds));
            command.add("-i");
            command.add(normalizedVideoPath.toString());

            if (titleImagePath == null) {
                command.add("-vf");
                command.add(createVideoFilter(normalizedSubtitlePath));
            } else {
                // PNG를 영상 끝까지 반복해 제목이 중간에 사라지지 않게 한다.
                command.add("-loop");
                command.add("1");
                command.add("-i");
                command.add(titleImagePath.toString());

                command.add("-filter_complex");
                command.add(
                        "[0:v]"
                                + createVideoFilter(normalizedSubtitlePath)
                                + "[base];"
                                + "[base][1:v]overlay=(W-w)/2:80:shortest=1[v]"
                );
                command.add("-map");
                command.add("[v]");
                command.add("-map");
                command.add("0:a?");
            }

            // 입력 파일이 모두 지정된 뒤 출력 구간 길이를 제한한다.
            command.add("-t");
            command.add(String.valueOf(durationSeconds));
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("veryfast");
            command.add("-c:a");
            command.add("aac");
            command.add("-movflags");
            command.add("+faststart");
            command.add(outputPath.toString());

            log.info(
                    "쇼츠 영상 생성 시작. videoPath={}, originalFileName={}, "
                            + "startMillis={}, endMillis={}, durationMillis={}, "
                            + "subtitlePath={}, title={}, titleImagePath={}, outputPath={}",
                    normalizedVideoPath,
                    originalFileName,
                    segment.startMillis(),
                    segment.endMillis(),
                    segment.endMillis() - segment.startMillis(),
                    normalizedSubtitlePath,
                    title,
                    titleImagePath,
                    outputPath
            );

            executeCommand(command);

            if (!Files.exists(outputPath)) {
                throw new IllegalStateException(
                        "FFmpeg 실행은 완료됐지만 쇼츠 영상이 생성되지 않았습니다."
                );
            }

            log.info(
                    "쇼츠 영상 생성 완료. outputPath={}, size={}, subtitles={}, titleOverlay={}",
                    outputPath,
                    Files.size(outputPath),
                    normalizedSubtitlePath != null,
                    titleImagePath != null
            );

            return outputPath;
        } catch (IOException exception) {
            deleteIfExists(outputPath);
            throw new IllegalStateException(
                    "쇼츠 영상 생성 중 파일 처리 오류가 발생했습니다.",
                    exception
            );
        } catch (RuntimeException exception) {
            deleteIfExists(outputPath);
            throw exception;
        } finally {
            deleteIfExists(titleImagePath);
        }
    }

    private String createVideoFilter(
            Path subtitlePath
    ) {
        StringBuilder filter =
                new StringBuilder(
                        "scale=1080:1920:force_original_aspect_ratio=increase,"
                                + "crop=1080:1920"
                );

        if (subtitlePath == null) {
            return filter.toString();
        }

        filter.append(
                ",subtitles='"
        );

        filter.append(
                escapeSubtitlePath(
                        subtitlePath
                )
        );

        filter.append("'");

        // ASS 파일 내부의 Shorts 스타일과 강조 색상을 그대로 사용한다.
        // SRT 등 외부 자막을 사용할 때는 FFmpeg 기본 스타일이 적용된다.
        return filter.toString();
    }

    private String escapeSubtitlePath(
            Path subtitlePath
    ) {
        String path =
                subtitlePath
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .replace(
                                "\\",
                                "/"
                        );

        path =
                path.replace(
                        ":",
                        "\\:"
                );

        path =
                path.replace(
                        "'",
                        "\\'"
                );

        return path;
    }

    private Path normalizeSubtitlePath(
            Path subtitlePath
    ) {
        if (subtitlePath == null) {
            return null;
        }

        Path normalizedPath =
                subtitlePath
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(normalizedPath)) {
            throw new IllegalArgumentException(
                    "쇼츠 자막 파일을 찾을 수 없습니다: "
                            + normalizedPath
            );
        }

        if (!Files.isRegularFile(normalizedPath)) {
            throw new IllegalArgumentException(
                    "유효한 쇼츠 자막 파일이 아닙니다: "
                            + normalizedPath
            );
        }

        return normalizedPath;
    }

    private void executeCommand(
            List<String> command
    ) {
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
                throw new IllegalStateException(
                        "FFmpeg 쇼츠 영상 생성에 실패했습니다."
                                + System.lineSeparator()
                                + output
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "FFmpeg 실행 중 오류가 발생했습니다.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "FFmpeg 실행이 중단되었습니다.",
                    exception
            );
        }
    }

    private Path createOutputPath(
            Path videoPath,
            String originalFileName
    ) {
        String fileName =
                originalFileName;

        if (
                fileName == null
                        || fileName.isBlank()
        ) {
            fileName =
                    videoPath
                            .getFileName()
                            .toString();
        }

        int extensionIndex =
                fileName.lastIndexOf(
                        "."
                );

        String baseName =
                extensionIndex > 0
                        ? fileName.substring(
                        0,
                        extensionIndex
                )
                        : fileName;

        String uniqueId =
                java.util.UUID
                        .randomUUID()
                        .toString()
                        .substring(
                                0,
                                8
                        );

        return videoPath
                .getParent()
                .resolve(
                        baseName
                                + "_shorts_"
                                + uniqueId
                                + ".mp4"
                )
                .normalize();
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
            Path videoPath,
            VideoHighlightSegment segment
    ) {
        if (videoPath == null) {
            throw new IllegalArgumentException(
                    "영상 파일 경로가 없습니다."
            );
        }

        Path normalizedPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(normalizedPath)) {
            throw new IllegalArgumentException(
                    "영상 파일을 찾을 수 없습니다: "
                            + normalizedPath
            );
        }

        if (!Files.isRegularFile(normalizedPath)) {
            throw new IllegalArgumentException(
                    "유효한 영상 파일이 아닙니다: "
                            + normalizedPath
            );
        }

        if (segment == null) {
            throw new IllegalArgumentException(
                    "쇼츠 구간이 없습니다."
            );
        }

        if (
                segment.startMillis() < 0
                        || segment.endMillis()
                        <= segment.startMillis()
        ) {
            throw new IllegalArgumentException(
                    "유효하지 않은 쇼츠 구간입니다."
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
                    "쇼츠 결과 파일 삭제 실패. path={}",
                    path,
                    exception
            );
        }
    }
}