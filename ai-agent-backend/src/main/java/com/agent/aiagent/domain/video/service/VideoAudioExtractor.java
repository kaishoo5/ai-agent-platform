package com.agent.aiagent.domain.video.service;

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
public class VideoAudioExtractor {

    public Path extract(
            Path videoPath
    ) {
        validateVideoPath(
                videoPath
        );

        Path normalizedVideoPath =
                videoPath
                        .toAbsolutePath()
                        .normalize();

        Path audioPath =
                createAudioPath(
                        normalizedVideoPath
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
                "영상 오디오 추출 시작. videoPath={}, audioPath={}",
                normalizedVideoPath,
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
                        "FFmpeg 오디오 추출에 실패했습니다."
                                + System.lineSeparator()
                                + output
                );
            }

            if (
                    !Files.exists(
                            audioPath
                    )
            ) {
                throw new IllegalStateException(
                        "FFmpeg 실행은 완료됐지만 오디오 파일이 생성되지 않았습니다."
                );
            }

            log.info(
                    "영상 오디오 추출 완료. videoPath={}, audioPath={}, size={}",
                    normalizedVideoPath,
                    audioPath,
                    Files.size(
                            audioPath
                    )
            );

            return audioPath;
        } catch (IOException exception) {
            deleteIfExists(
                    audioPath
            );

            throw new IllegalStateException(
                    "FFmpeg 실행 중 오류가 발생했습니다.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            deleteIfExists(
                    audioPath
            );

            throw new IllegalStateException(
                    "FFmpeg 실행이 중단되었습니다.",
                    exception
            );
        }
    }

    private Path createAudioPath(
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
                        baseName + ".wav"
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

    private void deleteIfExists(
            Path path
    ) {
        try {
            Files.deleteIfExists(
                    path
            );
        } catch (IOException exception) {
            log.warn(
                    "오디오 임시 파일 삭제 실패. path={}",
                    path,
                    exception
            );
        }
    }
}