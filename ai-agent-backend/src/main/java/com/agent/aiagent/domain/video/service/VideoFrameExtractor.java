package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Component
public class VideoFrameExtractor {

    private static final int FRAME_INTERVAL_SECONDS = 30;

    public List<VideoFrame> extract(Path videoPath) {
        validateVideoPath(
                videoPath
        );

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        Path frameDirectory =
                createFrameDirectory(
                        normalizedVideoPath
                );

        try {
            Files.createDirectories(
                    frameDirectory
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "영상 프레임 디렉터리 생성 중 오류가 발생했습니다.",
                    exception
            );
        }

        Path outputPattern =
                frameDirectory.resolve(
                        "frame_%06d.jpg"
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
                "-i"
        );

        command.add(
                normalizedVideoPath.toString()
        );

        command.add(
                "-vf"
        );

        command.add(
                "fps=1/" + FRAME_INTERVAL_SECONDS
        );

        command.add(
                "-q:v"
        );

        command.add(
                "2"
        );

        command.add(
                outputPattern.toString()
        );

        log.info(
                "영상 프레임 추출 시작. videoPath={}, frameDirectory={}, intervalSeconds={}",
                normalizedVideoPath,
                frameDirectory,
                FRAME_INTERVAL_SECONDS
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
                deleteDirectory(
                        frameDirectory
                );

                throw new IllegalStateException(
                        "FFmpeg 프레임 추출에 실패했습니다."
                                + System.lineSeparator()
                                + output
                );
            }

            List<Path> framePaths;

            try (Stream<Path> stream = Files.list(frameDirectory)) {
                framePaths = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                .toString()
                                .toLowerCase()
                                .endsWith(".jpg"))
                        .sorted()
                        .toList();
            }

            List<VideoFrame> frames = IntStream.range(0, framePaths.size())
                    .mapToObj(index -> new VideoFrame(
                            index * FRAME_INTERVAL_SECONDS * 1_000L,
                            framePaths.get(index)
                    ))
                    .toList();

            log.info(
                    "영상 프레임 추출 완료. videoPath={}, frameDirectory={}, frameCount={}",
                    videoPath,
                    frameDirectory,
                    frames.size()
            );

            return frames;
        } catch (IOException exception) {
            deleteDirectory(
                    frameDirectory
            );

            throw new IllegalStateException(
                    "FFmpeg 실행 중 오류가 발생했습니다.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            deleteDirectory(
                    frameDirectory
            );

            throw new IllegalStateException(
                    "FFmpeg 실행이 중단되었습니다.",
                    exception
            );
        }
    }

    private Path createFrameDirectory(
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
                        baseName + "_frames"
                )
                .normalize();
    }

    private List<Path> findFramePaths(
            Path frameDirectory
    ) throws IOException {
        try (
                Stream<Path> stream =
                        Files.list(
                                frameDirectory
                        )
        ) {
            return stream
                    .filter(
                            Files::isRegularFile
                    )
                    .filter(path ->
                            path.getFileName()
                                    .toString()
                                    .toLowerCase()
                                    .endsWith(".jpg")
                    )
                    .sorted(
                            Comparator.comparing(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                            )
                    )
                    .toList();
        }
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

    private void validateVideoPath(
            Path videoPath
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

        if (
                !Files.exists(
                        normalizedPath
                )
        ) {
            throw new IllegalArgumentException(
                    "영상 파일을 찾을 수 없습니다: "
                            + normalizedPath
            );
        }

        if (
                !Files.isRegularFile(
                        normalizedPath
                )
        ) {
            throw new IllegalArgumentException(
                    "유효한 영상 파일이 아닙니다: "
                            + normalizedPath
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
                Stream<Path> stream =
                        Files.walk(
                                directory
                        )
        ) {
            stream
                    .sorted(
                            Comparator.reverseOrder()
                    )
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(
                                    path
                            );
                        } catch (IOException exception) {
                            log.warn(
                                    "영상 프레임 임시 파일 삭제 실패. path={}",
                                    path,
                                    exception
                            );
                        }
                    });
        } catch (IOException exception) {
            log.warn(
                    "영상 프레임 디렉터리 삭제 실패. directory={}",
                    directory,
                    exception
            );
        }
    }

    public void cleanup(
            List<VideoFrame> frames
    ) {
        if (
                frames == null
                        || frames.isEmpty()
        ) {
            return;
        }

        Path frameDirectory =
                frames.getFirst()
                        .path()
                        .getParent();

        if (frameDirectory == null) {
            return;
        }

        deleteDirectory(
                frameDirectory
        );

        log.info(
                "영상 프레임 임시 디렉터리 삭제 완료. frameDirectory={}",
                frameDirectory
        );
    }
}