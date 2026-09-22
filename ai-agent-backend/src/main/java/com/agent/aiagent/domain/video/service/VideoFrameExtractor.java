package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.service.FileAnalysisCancellationManager;
import com.agent.aiagent.domain.file.service.FileAnalysisCancelledException;
import com.agent.aiagent.domain.video.model.VideoFrame;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoFrameExtractor {

    private static final int FRAME_INTERVAL_SECONDS =
            30;

    private static final int MAX_FRAME_COUNT =
            30;

    private final FileAnalysisCancellationManager cancellationManager;

    public List<VideoFrame> extract(
            String fileId,
            Path videoPath
    ) {
        validateVideoPath(
                videoPath
        );

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        long durationSeconds =
                getVideoDurationSeconds(
                        fileId,
                        normalizedVideoPath
                );

        int frameIntervalSeconds =
                calculateFrameIntervalSeconds(
                        durationSeconds
                );

        log.info(
                "영상 프레임 추출 간격 결정. videoPath={}, durationSeconds={}, baseIntervalSeconds={}, frameIntervalSeconds={}, maxFrameCount={}",
                normalizedVideoPath,
                durationSeconds,
                FRAME_INTERVAL_SECONDS,
                frameIntervalSeconds,
                MAX_FRAME_COUNT
        );

        Path frameDirectory =
                createFrameDirectory(
                        normalizedVideoPath
                );

        try {
            Files.createDirectories(
                    frameDirectory
            );
        } catch (IOException exception) {
            cancellationManager.checkCancelled(
                    fileId
            );

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
                "fps=1/" + frameIntervalSeconds
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
                "영상 프레임 추출 시작. videoPath={}, frameDirectory={}, intervalSeconds={}, durationSeconds={}, maxFrameCount={}",
                normalizedVideoPath,
                frameDirectory,
                frameIntervalSeconds,
                durationSeconds,
                MAX_FRAME_COUNT
        );

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        command
                );

        processBuilder.redirectErrorStream(
                true
        );

        cancellationManager.checkCancelled(
                fileId
        );

        Process process =
                null;

        try {
            process =
                    processBuilder.start();

            cancellationManager.registerProcess(
                    fileId,
                    process
            );

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

            try (
                    Stream<Path> stream =
                            Files.list(
                                    frameDirectory
                            )
            ) {
                framePaths =
                        stream
                                .filter(
                                        Files::isRegularFile
                                )
                                .filter(path ->
                                        path.getFileName()
                                                .toString()
                                                .toLowerCase()
                                                .endsWith(".jpg")
                                )
                                .sorted()
                                .toList();
            }

            List<VideoFrame> frames =
                    IntStream.range(
                                    0,
                                    framePaths.size()
                            )
                            .mapToObj(index ->
                                    new VideoFrame(
                                            index
                                                    * frameIntervalSeconds
                                                    * 1_000L,
                                            framePaths.get(
                                                    index
                                            )
                                    )
                            )
                            .toList();

            log.info(
                    "영상 프레임 추출 완료. videoPath={}, frameDirectory={}, frameCount={}, intervalSeconds={}, durationSeconds={}",
                    videoPath,
                    frameDirectory,
                    frames.size(),
                    frameIntervalSeconds,
                    durationSeconds
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
            Thread.currentThread()
                    .interrupt();

            deleteDirectory(
                    frameDirectory
            );

            throw new FileAnalysisCancelledException(
                    fileId,
                    exception
            );
        } finally {
            if (process != null) {
                cancellationManager.unregisterProcess(
                        fileId,
                        process
                );
            }
        }
    }

    private long getVideoDurationSeconds(
            String fileId,
            Path videoPath
    ) {
        List<String> command =
                new ArrayList<>();

        command.add(
                "ffprobe"
        );

        command.add(
                "-v"
        );

        command.add(
                "error"
        );

        command.add(
                "-show_entries"
        );

        command.add(
                "format=duration"
        );

        command.add(
                "-of"
        );

        command.add(
                "default=noprint_wrappers=1:nokey=1"
        );

        command.add(
                videoPath.toString()
        );

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        command
                );

        processBuilder.redirectErrorStream(
                true
        );

        cancellationManager.checkCancelled(
                fileId
        );

        Process process =
                null;

        try {
            process =
                    processBuilder.start();

            cancellationManager.registerProcess(
                    fileId,
                    process
            );

            String output =
                    readProcessOutput(
                            process
                    )
                            .trim();

            int exitCode =
                    process.waitFor();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        "영상 길이 조회에 실패했습니다."
                                + System.lineSeparator()
                                + output
                );
            }

            if (output.isBlank()) {
                throw new IllegalStateException(
                        "영상 길이 조회 결과가 없습니다."
                );
            }

            double duration =
                    Double.parseDouble(
                            output
                    );

            long durationSeconds =
                    Math.max(
                            1L,
                            (long) Math.ceil(
                                    duration
                            )
                    );

            log.info(
                    "영상 길이 조회 완료. videoPath={}, durationSeconds={}",
                    videoPath,
                    durationSeconds
            );

            return durationSeconds;
        } catch (IOException exception) {
            cancellationManager.checkCancelled(
                    fileId
            );

            throw new IllegalStateException(
                    "FFprobe 실행 중 오류가 발생했습니다.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            throw new FileAnalysisCancelledException(
                    fileId,
                    exception
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "영상 길이 값을 변환할 수 없습니다.",
                    exception
            );
        } finally {
            if (process != null) {
                cancellationManager.unregisterProcess(
                        fileId,
                        process
                );
            }
        }
    }

    private int calculateFrameIntervalSeconds(
            long durationSeconds
    ) {
        long dynamicIntervalSeconds =
                (
                        durationSeconds
                                + MAX_FRAME_COUNT
                                - 1
                )
                        / MAX_FRAME_COUNT;

        long intervalSeconds =
                Math.max(
                        FRAME_INTERVAL_SECONDS,
                        dynamicIntervalSeconds
                );

        if (intervalSeconds > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) intervalSeconds;
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