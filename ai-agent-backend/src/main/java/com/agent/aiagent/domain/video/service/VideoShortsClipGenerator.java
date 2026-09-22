package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
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
public class VideoShortsClipGenerator {

    public Path generate(
            Path videoPath,
            VideoHighlightSegment segment,
            Path subtitlePath,
            String originalFileName
    ) {
        validate(
                videoPath,
                segment
        );

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        Path normalizedSubtitlePath =
                normalizeSubtitlePath(
                        subtitlePath
                );

        Path outputPath =
                createOutputPath(
                        normalizedVideoPath,
                        originalFileName
                );

        double startSeconds =
                segment.startMillis()
                        / 1000.0;

        double durationSeconds =
                (
                        segment.endMillis()
                                - segment.startMillis()
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
                normalizedVideoPath.toString()
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
                "-vf"
        );

        command.add(
                createVideoFilter(
                        normalizedSubtitlePath
                )
        );

        command.add(
                "-c:v"
        );

        command.add(
                "libx264"
        );

        command.add(
                "-preset"
        );

        command.add(
                "veryfast"
        );

        command.add(
                "-c:a"
        );

        command.add(
                "aac"
        );

        command.add(
                "-movflags"
        );

        command.add(
                "+faststart"
        );

        command.add(
                outputPath.toString()
        );

        log.info(
                "쇼츠 영상 생성 시작. videoPath={}, originalFileName={}, startMillis={}, endMillis={}, durationMillis={}, subtitlePath={}, outputPath={}",
                normalizedVideoPath,
                originalFileName,
                segment.startMillis(),
                segment.endMillis(),
                segment.endMillis()
                        - segment.startMillis(),
                normalizedSubtitlePath,
                outputPath
        );

        try {
            executeCommand(
                    command
            );

            if (!Files.exists(outputPath)) {
                throw new IllegalStateException(
                        "FFmpeg 실행은 완료됐지만 쇼츠 영상이 생성되지 않았습니다."
                );
            }

            log.info(
                    "쇼츠 영상 생성 완료. outputPath={}, size={}, subtitles={}",
                    outputPath,
                    Files.size(
                            outputPath
                    ),
                    normalizedSubtitlePath != null
            );

            return outputPath;
        } catch (IOException exception) {
            deleteIfExists(
                    outputPath
            );

            throw new IllegalStateException(
                    "쇼츠 영상 생성 중 파일 처리 오류가 발생했습니다.",
                    exception
            );
        } catch (RuntimeException exception) {
            deleteIfExists(
                    outputPath
            );

            throw exception;
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

        filter.append(
                "':force_style='"
        );

        filter.append(
                "FontSize=16,"
                        + "PrimaryColour=&H00FFFFFF,"
                        + "OutlineColour=&H00000000,"
                        + "BorderStyle=1,"
                        + "Outline=2,"
                        + "Shadow=1,"
                        + "Alignment=2,"
                        + "MarginV=30"
        );

        filter.append(
                "'"
        );

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