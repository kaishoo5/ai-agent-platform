package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.service.FileAnalysisCancellationManager;
import com.agent.aiagent.domain.file.service.FileAnalysisCancelledException;

import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.model.VideoTranscriptSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
public class WhisperTranscriber {

    private final FileAnalysisCancellationManager cancellationManager;

    private final ObjectMapper objectMapper;

    @Value("${app.whisper.executable-path}")
    private String executablePath;

    @Value("${app.whisper.model-path}")
    private String modelPath;

    public VideoTranscript transcribe(
            String fileId,
            Path audioPath
    ) {
        validateAudioPath(
                audioPath
        );

        Path normalizedAudioPath =
                audioPath
                        .toAbsolutePath()
                        .normalize();

        Path outputBasePath =
                createOutputBasePath(
                        normalizedAudioPath
                );

        Path jsonPath =
                Path.of(
                        outputBasePath + ".json"
                );

        List<String> command =
                List.of(
                        executablePath,
                        "-m",
                        modelPath,
                        "-f",
                        normalizedAudioPath.toString(),
                        "-l",
                        "auto",
                        "-oj",
                        "-of",
                        outputBasePath.toString()
                );

        log.info(
                "Whisper STT 시작. audioPath={}",
                normalizedAudioPath
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

        Process process = null;

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

            cancellationManager.checkCancelled(
                    fileId
            );

            if (exitCode != 0) {
                throw new IllegalStateException(
                        "Whisper STT 실행에 실패했습니다."
                                + System.lineSeparator()
                                + output
                );
            }

            if (
                    !Files.exists(
                            jsonPath
                    )
            ) {
                throw new IllegalStateException(
                        "Whisper 실행은 완료됐지만 JSON 결과 파일이 생성되지 않았습니다."
                                + System.lineSeparator()
                                + output
                );
            }

            VideoTranscript transcript =
                    parseTranscript(
                            jsonPath
                    );

            log.info(
                    "Whisper STT 완료. audioPath={}, segmentCount={}, language={}",
                    normalizedAudioPath,
                    transcript.segments().size(),
                    transcript.language()
            );

            return transcript;
        } catch (IOException exception) {
            cancellationManager.checkCancelled(
                    fileId
            );
            throw new IllegalStateException(
                    "Whisper 실행 중 오류가 발생했습니다.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

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

            deleteIfExists(
                    jsonPath
            );
        }
    }

    private VideoTranscript parseTranscript(
            Path jsonPath
    ) throws IOException {
        JsonNode root =
                objectMapper.readTree(
                        jsonPath.toFile()
                );

        String language =
                getLanguage(
                        root
                );

        JsonNode transcriptionNode =
                root.get(
                        "transcription"
                );

        List<VideoTranscriptSegment> segments =
                new ArrayList<>();

        StringBuilder fullText =
                new StringBuilder();

        if (
                transcriptionNode != null
                        && transcriptionNode.isArray()
        ) {
            for (JsonNode item : transcriptionNode) {
                JsonNode offsets =
                        item.get(
                                "offsets"
                        );

                long startMillis =
                        offsets != null
                                ? offsets.path(
                                "from"
                        ).asLong()
                                : 0L;

                long endMillis =
                        offsets != null
                                ? offsets.path(
                                "to"
                        ).asLong()
                                : 0L;

                String text =
                        item.path(
                                        "text"
                                )
                                .asText(
                                        ""
                                )
                                .trim();

                if (text.isBlank()) {
                    continue;
                }

                segments.add(
                        new VideoTranscriptSegment(
                                startMillis,
                                endMillis,
                                text
                        )
                );

                if (!fullText.isEmpty()) {
                    fullText.append(
                            System.lineSeparator()
                    );
                }

                fullText.append(
                        text
                );
            }
        }

        return new VideoTranscript(
                language,
                fullText.toString(),
                List.copyOf(
                        segments
                )
        );
    }

    private String getLanguage(
            JsonNode root
    ) {
        JsonNode resultNode =
                root.get(
                        "result"
                );

        if (resultNode == null) {
            return "";
        }

        return resultNode
                .path(
                        "language"
                )
                .asText(
                        ""
                );
    }

    private Path createOutputBasePath(
            Path audioPath
    ) {
        String fileName =
                audioPath
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

        return audioPath
                .getParent()
                .resolve(
                        baseName + "-transcript"
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

    private void validateAudioPath(
            Path audioPath
    ) {
        if (audioPath == null) {
            throw new IllegalArgumentException(
                    "오디오 파일 경로가 없습니다."
            );
        }

        Path normalizedPath =
                audioPath
                        .toAbsolutePath()
                        .normalize();

        if (
                !Files.exists(
                        normalizedPath
                )
        ) {
            throw new IllegalArgumentException(
                    "오디오 파일을 찾을 수 없습니다: "
                            + normalizedPath
            );
        }

        if (
                !Files.isRegularFile(
                        normalizedPath
                )
        ) {
            throw new IllegalArgumentException(
                    "유효한 오디오 파일이 아닙니다: "
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
                    "Whisper 임시 JSON 삭제 실패. path={}",
                    path,
                    exception
            );
        }
    }
}