package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import com.agent.aiagent.domain.video.model.VideoHighlightSelection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class VideoClipGenerator {

    public Path generate(
            Path videoPath,
            VideoHighlightSelection selection
    ) {
        validate(
                videoPath,
                selection
        );

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        List<VideoHighlightSegment> segments =
                selection.segments()
                        .stream()
                        .filter(segment ->
                                segment != null
                                        && segment.startMillis() >= 0
                                        && segment.endMillis()
                                        > segment.startMillis()
                        )
                        .sorted(
                                Comparator.comparingLong(
                                        VideoHighlightSegment::startMillis
                                )
                        )
                        .toList();

        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "생성할 하이라이트 구간이 없습니다."
            );
        }

        Path outputPath =
                createOutputPath(
                        normalizedVideoPath
                );

        Path tempDirectory =
                createTempDirectory(
                        normalizedVideoPath
                );

        try {
            List<Path> clips =
                    createClips(
                            normalizedVideoPath,
                            segments,
                            tempDirectory
                    );

            concatClips(
                    clips,
                    tempDirectory,
                    outputPath
            );

            if (!Files.exists(outputPath)) {
                throw new IllegalStateException(
                        "FFmpeg 실행은 완료됐지만 요약 영상이 생성되지 않았습니다."
                );
            }

            log.info(
                    "영상 요약본 생성 완료. videoPath={}, outputPath={}, segmentCount={}, size={}",
                    normalizedVideoPath,
                    outputPath,
                    segments.size(),
                    Files.size(
                            outputPath
                    )
            );

            return outputPath;
        } catch (IOException exception) {
            deleteIfExists(
                    outputPath
            );

            throw new IllegalStateException(
                    "영상 요약본 생성 중 파일 처리 오류가 발생했습니다.",
                    exception
            );
        } catch (RuntimeException exception) {
            deleteIfExists(
                    outputPath
            );

            throw exception;
        } finally {
            deleteDirectory(
                    tempDirectory
            );
        }
    }

    private List<Path> createClips(
            Path videoPath,
            List<VideoHighlightSegment> segments,
            Path tempDirectory
    ) {
        List<Path> clips =
                new ArrayList<>();

        for (
                int index = 0;
                index < segments.size();
                index++
        ) {
            VideoHighlightSegment segment =
                    segments.get(index);

            Path clipPath =
                    tempDirectory.resolve(
                            "clip_%03d.mp4".formatted(
                                    index
                            )
                    );

            createClip(
                    videoPath,
                    segment,
                    clipPath,
                    index
            );

            clips.add(
                    clipPath
            );
        }

        return clips;
    }

    private void createClip(
            Path videoPath,
            VideoHighlightSegment segment,
            Path clipPath,
            int index
    ) {
        double startSeconds =
                segment.startMillis() / 1000.0;

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
                clipPath.toString()
        );

        log.info(
                "영상 하이라이트 Clip 생성 시작. index={}, startMillis={}, endMillis={}, clipPath={}",
                index,
                segment.startMillis(),
                segment.endMillis(),
                clipPath
        );

        executeCommand(
                command,
                "FFmpeg 영상 Clip 생성에 실패했습니다."
        );

        if (!Files.exists(clipPath)) {
            throw new IllegalStateException(
                    "FFmpeg 실행은 완료됐지만 Clip 파일이 생성되지 않았습니다: "
                            + clipPath
            );
        }

        log.info(
                "영상 하이라이트 Clip 생성 완료. index={}, clipPath={}",
                index,
                clipPath
        );
    }

    private void concatClips(
            List<Path> clips,
            Path tempDirectory,
            Path outputPath
    ) throws IOException {
        if (clips.isEmpty()) {
            throw new IllegalArgumentException(
                    "병합할 Clip이 없습니다."
            );
        }

        Path concatFile =
                tempDirectory.resolve(
                        "concat.txt"
                );

        List<String> lines =
                clips.stream()
                        .map(path ->
                                "file '"
                                        + escapeConcatPath(
                                        path.toAbsolutePath()
                                                .normalize()
                                                .toString()
                                )
                                        + "'"
                        )
                        .toList();

        Files.write(
                concatFile,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        List<String> command =
                new ArrayList<>();

        command.add(
                "ffmpeg"
        );

        command.add(
                "-y"
        );

        command.add(
                "-f"
        );

        command.add(
                "concat"
        );

        command.add(
                "-safe"
        );

        command.add(
                "0"
        );

        command.add(
                "-i"
        );

        command.add(
                concatFile.toString()
        );

        command.add(
                "-c"
        );

        command.add(
                "copy"
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
                "영상 하이라이트 Clip 병합 시작. clipCount={}, outputPath={}",
                clips.size(),
                outputPath
        );

        executeCommand(
                command,
                "FFmpeg 영상 Clip 병합에 실패했습니다."
        );
    }

    private void executeCommand(
            List<String> command,
            String failureMessage
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
                        failureMessage
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
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "FFmpeg 실행이 중단되었습니다.",
                    exception
            );
        }
    }

    private Path createOutputPath(
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

        String baseName =
                extensionIndex > 0
                        ? fileName.substring(
                        0,
                        extensionIndex
                )
                        : fileName;

        return videoPath
                .getParent()
                .resolve(
                        baseName + "_summary.mp4"
                )
                .normalize();
    }

    private Path createTempDirectory(
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

        String baseName =
                extensionIndex > 0
                        ? fileName.substring(
                        0,
                        extensionIndex
                )
                        : fileName;

        Path tempDirectory =
                videoPath
                        .getParent()
                        .resolve(
                                baseName + "_summary_temp"
                        )
                        .normalize();

        try {
            deleteDirectory(
                    tempDirectory
            );

            Files.createDirectories(
                    tempDirectory
            );

            return tempDirectory;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "영상 요약 임시 디렉터리를 생성하지 못했습니다: "
                            + tempDirectory,
                    exception
            );
        }
    }

    private String escapeConcatPath(
            String path
    ) {
        return path.replace(
                "'",
                "'\\''"
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
            Path videoPath,
            VideoHighlightSelection selection
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

        if (
                selection == null
                        || selection.segments() == null
                        || selection.segments().isEmpty()
        ) {
            throw new IllegalArgumentException(
                    "영상 하이라이트 선정 결과가 없습니다."
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
                    "영상 요약 결과 파일 삭제 실패. path={}",
                    path,
                    exception
            );
        }
    }

    private void deleteDirectory(
            Path directory
    ) {
        if (
                directory == null
                        || !Files.exists(
                        directory
                )
        ) {
            return;
        }

        try (
                var paths =
                        Files.walk(
                                directory
                        )
        ) {
            paths.sorted(
                            Comparator.reverseOrder()
                    )
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(
                                    path
                            );
                        } catch (IOException exception) {
                            log.warn(
                                    "영상 요약 임시 파일 삭제 실패. path={}",
                                    path,
                                    exception
                            );
                        }
                    });
        } catch (IOException exception) {
            log.warn(
                    "영상 요약 임시 디렉터리 삭제 실패. directory={}",
                    directory,
                    exception
            );
        }
    }
}