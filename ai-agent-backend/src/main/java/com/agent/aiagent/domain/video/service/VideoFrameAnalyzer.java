package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.service.FileAnalysisCancellationManager;
import com.agent.aiagent.domain.file.service.FileAnalysisCancelledException;
import com.agent.aiagent.domain.video.model.VideoFrame;
import com.agent.aiagent.domain.video.model.VideoFrameAnalysis;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoFrameAnalyzer {

    private static final String USER_ROLE = "user";

    private static final int VISION_CONCURRENCY = 2;

    private final ChatModelProvider chatModelProvider;
    private final FileAnalysisCancellationManager cancellationManager;

    public List<VideoFrameAnalysis> analyze(
            String fileId,
            List<VideoFrame> frames
    ) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        cancellationManager.checkCancelled(
                fileId
        );

        long startedAt =
                System.currentTimeMillis();

        log.info(
                "영상 전체 프레임 Vision 분석 시작. frameCount={}, concurrency={}",
                frames.size(),
                VISION_CONCURRENCY
        );

        ExecutorService executorService =
                Executors.newFixedThreadPool(
                        VISION_CONCURRENCY
                );

        List<CompletableFuture<IndexedFrameAnalysis>> futures =
                new ArrayList<>();

        try {
            for (
                    int index = 0;
                    index < frames.size();
                    index++
            ) {
                cancellationManager.checkCancelled(
                        fileId
                );

                int frameIndex = index;
                VideoFrame frame = frames.get(index);

                CompletableFuture<IndexedFrameAnalysis> future =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    cancellationManager.checkCancelled(
                                            fileId
                                    );

                                    return analyzeFrame(
                                            fileId,
                                            frameIndex,
                                            frames.size(),
                                            frame
                                    );
                                },
                                executorService
                        );

                futures.add(
                        future
                );
            }

            List<IndexedFrameAnalysis> indexedAnalyses =
                    new ArrayList<>();

            for (CompletableFuture<IndexedFrameAnalysis> future : futures) {
                cancellationManager.checkCancelled(
                        fileId
                );

                try {
                    indexedAnalyses.add(
                            future.get()
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();

                    throw new FileAnalysisCancelledException(
                            fileId,
                            exception
                    );
                } catch (ExecutionException exception) {
                    Throwable cause =
                            exception.getCause();

                    if (cause instanceof FileAnalysisCancelledException cancelledException) {
                        throw cancelledException;
                    }

                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }

                    throw new IllegalStateException(
                            "영상 프레임 Vision 분석 중 오류가 발생했습니다.",
                            cause
                    );
                }
            }

            List<VideoFrameAnalysis> analyses =
                    indexedAnalyses.stream()
                            .sorted(
                                    Comparator.comparingInt(
                                            IndexedFrameAnalysis::index
                                    )
                            )
                            .map(
                                    IndexedFrameAnalysis::analysis
                            )
                            .toList();

            long elapsedMillis =
                    System.currentTimeMillis()
                            - startedAt;

            log.info(
                    "영상 전체 프레임 Vision 분석 완료. frameCount={}, concurrency={}, elapsedMillis={}",
                    analyses.size(),
                    VISION_CONCURRENCY,
                    elapsedMillis
            );

            return analyses;
        } finally {
            futures.forEach(future ->
                    future.cancel(
                            true
                    )
            );

            executorService.shutdownNow();
        }
    }

    public String analyze(
            Path framePath
    ) {
        validateFramePath(
                framePath
        );

        Path normalizedFramePath =
                framePath
                        .toAbsolutePath()
                        .normalize();

        String encodedImage =
                encodeImage(
                        normalizedFramePath
                );

        ChatModelMessage message =
                new ChatModelMessage(
                        USER_ROLE,
                        """
                        이 이미지는 영상에서 추출한 프레임입니다.

                        화면에 실제로 보이는 내용을 한국어로 설명하세요.

                        다음 내용을 중심으로 간결하게 설명하세요.
                        - 등장하는 사람
                        - 사람의 행동
                        - 장소 또는 배경
                        - 눈에 띄는 물체
                        - 화면에서 확인 가능한 상황

                        이미지에서 확인할 수 없는 내용은 추측하지 마세요.
                        """,
                        List.of(
                                encodedImage
                        )
                );

        log.info(
                "영상 프레임 Vision 분석 시작. framePath={}",
                normalizedFramePath
        );

        ChatModelResponse response =
                chatModelProvider.chatOnce(
                        new ChatModelRequest(
                                ChatModelType.VISION,
                                List.of(
                                        message
                                ),
                                List.of()
                        )
                );

        String content =
                response.content();

        if (
                content == null
                        || content.isBlank()
        ) {
            throw new IllegalStateException(
                    "영상 프레임 Vision 분석 결과가 없습니다."
            );
        }

        String result =
                content.trim();

        log.info(
                "영상 프레임 Vision 분석 완료. framePath={}, result={}",
                normalizedFramePath,
                result
        );

        return result;
    }

    private IndexedFrameAnalysis analyzeFrame(
            String fileId,
            int index,
            int totalCount,
            VideoFrame frame
    ) {
        cancellationManager.checkCancelled(
                fileId
        );

        String description =
                analyze(
                        frame.path()
                );

        cancellationManager.checkCancelled(
                fileId
        );

        log.info(
                "영상 프레임 분석 진행. index={}, total={}, timestampMillis={}, framePath={}",
                index + 1,
                totalCount,
                frame.timestampMillis(),
                frame.path()
        );

        return new IndexedFrameAnalysis(
                index,
                new VideoFrameAnalysis(
                        frame.timestampMillis(),
                        description
                )
        );
    }

    private String encodeImage(
            Path framePath
    ) {
        try {
            byte[] imageBytes =
                    Files.readAllBytes(
                            framePath
                    );

            return Base64
                    .getEncoder()
                    .encodeToString(
                            imageBytes
                    );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "영상 프레임 이미지 인코딩 중 오류가 발생했습니다.",
                    exception
            );
        }
    }

    private void validateFramePath(
            Path framePath
    ) {
        if (framePath == null) {
            throw new IllegalArgumentException(
                    "영상 프레임 경로가 없습니다."
            );
        }

        Path normalizedPath =
                framePath
                        .toAbsolutePath()
                        .normalize();

        if (
                !Files.exists(
                        normalizedPath
                )
        ) {
            throw new IllegalArgumentException(
                    "영상 프레임 파일을 찾을 수 없습니다: "
                            + normalizedPath
            );
        }

        if (
                !Files.isRegularFile(
                        normalizedPath
                )
        ) {
            throw new IllegalArgumentException(
                    "유효한 영상 프레임 파일이 아닙니다: "
                            + normalizedPath
            );
        }
    }

    private record IndexedFrameAnalysis(
            int index,
            VideoFrameAnalysis analysis
    ) {
    }
}