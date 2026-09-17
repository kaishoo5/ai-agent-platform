package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.rag.model.ChatSource;
import com.agent.aiagent.domain.video.model.VideoSummaryResult;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamingExecutor {

    private static final long SSE_TIMEOUT = 300_000L;

    private final ChatModelProvider chatModelProvider;
    private final ChatPersistenceService chatPersistenceService;
    private final ObjectMapper objectMapper;

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest
    ) {
        return execute(
                request,
                chatModelRequest,
                null,
                List.of()
        );
    }

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest,
            VideoSummaryResult videoSummaryResult
    ) {
        return execute(
                request,
                chatModelRequest,
                videoSummaryResult,
                List.of()
        );
    }

    public SseEmitter execute(
            ChatRequest request,
            ChatModelRequest chatModelRequest,
            VideoSummaryResult videoSummaryResult,
            List<ChatSource> sources
    ) {
        SseEmitter emitter =
                new SseEmitter(
                        SSE_TIMEOUT
                );

        String roomId =
                request.getRoomId();

        StringBuilder assistantContent =
                new StringBuilder();

        AtomicBoolean completed =
                new AtomicBoolean(false);

        AtomicBoolean terminated =
                new AtomicBoolean(false);

        AtomicBoolean responseSaved =
                new AtomicBoolean(false);

        List<ChatSource> safeSources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );

        sendSources(
                emitter,
                safeSources,
                terminated
        );

        if (terminated.get()) {
            return emitter;
        }

        sendVideoSummaryResult(
                emitter,
                videoSummaryResult,
                terminated
        );

        if (terminated.get()) {
            return emitter;
        }

        Disposable disposable = chatModelProvider
                .chat(
                        chatModelRequest
                )
                .subscribe(
                        response -> {
                            if (terminated.get()) {
                                return;
                            }

                            String chunk =
                                    response.content();

                            if (
                                    chunk == null
                                            || chunk.isEmpty()
                            ) {
                                return;
                            }

                            assistantContent.append(
                                    chunk
                            );

                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name("message")
                                                .data(
                                                        objectMapper.writeValueAsString(
                                                                chunk
                                                        )
                                                )
                                );
                            } catch (
                                    AsyncRequestNotUsableException exception
                            ) {
                                if (
                                        terminated.compareAndSet(
                                                false,
                                                true
                                        )
                                ) {
                                    saveInterruptedResponse(
                                            request,
                                            roomId,
                                            assistantContent,
                                            responseSaved
                                    );
                                }

                                log.info(
                                        "클라이언트가 SSE 연결을 종료했습니다. roomId={}",
                                        roomId
                                );
                            } catch (IOException exception) {
                                if (
                                        terminated.compareAndSet(
                                                false,
                                                true
                                        )
                                ) {
                                    saveInterruptedResponse(
                                            request,
                                            roomId,
                                            assistantContent,
                                            responseSaved
                                    );
                                }

                                log.info(
                                        "SSE 응답 전송 중 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (Exception exception) {
                                terminated.set(
                                        true
                                );

                                log.error(
                                        "AI 응답 전송 중 오류가 발생했습니다. roomId={}",
                                        roomId,
                                        exception
                                );

                                emitter.completeWithError(
                                        exception
                                );
                            }
                        },
                        error -> {
                            if (
                                    terminated.get()
                                            || !completed.compareAndSet(
                                            false,
                                            true
                                    )
                            ) {
                                return;
                            }

                            log.error(
                                    "AI 스트리밍 호출 중 오류가 발생했습니다. roomId={}",
                                    roomId,
                                    error
                            );

                            emitter.completeWithError(
                                    error
                            );
                        },
                        () -> {
                            if (
                                    terminated.get()
                                            || !completed.compareAndSet(
                                            false,
                                            true
                                    )
                            ) {
                                return;
                            }

                            try {
                                if (
                                        responseSaved.compareAndSet(
                                                false,
                                                true
                                        )
                                ) {
                                    String videoResultJson =
                                            videoSummaryResult == null
                                                    ? null
                                                    : objectMapper.writeValueAsString(
                                                    videoSummaryResult
                                            );

                                    String sourceResultJson =
                                            safeSources.isEmpty()
                                                    ? null
                                                    : objectMapper.writeValueAsString(
                                                    safeSources
                                            );

                                    if (request.isRegenerate()) {
                                        chatPersistenceService
                                                .replaceLastAssistantMessage(
                                                        roomId,
                                                        assistantContent.toString(),
                                                        videoResultJson,
                                                        sourceResultJson
                                                );
                                    } else {
                                        chatPersistenceService
                                                .saveAssistantMessage(
                                                        roomId,
                                                        assistantContent.toString(),
                                                        videoResultJson,
                                                        sourceResultJson
                                                );
                                    }
                                }

                                emitter.send(
                                        SseEmitter.event()
                                                .name("done")
                                                .data("")
                                );

                                emitter.complete();
                            } catch (
                                    AsyncRequestNotUsableException exception
                            ) {
                                terminated.set(
                                        true
                                );

                                log.info(
                                        "완료 응답 전송 전에 클라이언트 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (IOException exception) {
                                terminated.set(
                                        true
                                );

                                log.info(
                                        "완료 응답 전송 중 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (Exception exception) {
                                terminated.set(
                                        true
                                );

                                log.error(
                                        "AI 응답 저장 또는 SSE 완료 처리 중 오류가 발생했습니다. roomId={}",
                                        roomId,
                                        exception
                                );

                                emitter.completeWithError(
                                        exception
                                );
                            }
                        }
                );

        configureEmitterCallbacks(
                emitter,
                disposable,
                request,
                roomId,
                assistantContent,
                completed,
                terminated,
                responseSaved
        );

        return emitter;
    }

    private void sendSources(
            SseEmitter emitter,
            List<ChatSource> sources,
            AtomicBoolean terminated
    ) {
        if (
                sources == null
                        || sources.isEmpty()
        ) {
            return;
        }

        try {
            emitter.send(
                    SseEmitter.event()
                            .name("source_result")
                            .data(
                                    objectMapper.writeValueAsString(
                                            sources
                                    )
                            )
            );

            log.info(
                    "RAG 출처 SSE 전송 완료. sourceCount={}",
                    sources.size()
            );
        } catch (
                AsyncRequestNotUsableException exception
        ) {
            terminated.set(
                    true
            );

            log.info(
                    "RAG 출처 전송 전에 클라이언트 연결이 종료되었습니다."
            );
        } catch (IOException exception) {
            terminated.set(
                    true
            );

            log.info(
                    "RAG 출처 SSE 전송 중 연결이 종료되었습니다."
            );
        } catch (Exception exception) {
            terminated.set(
                    true
            );

            log.error(
                    "RAG 출처 SSE 전송 중 오류가 발생했습니다.",
                    exception
            );

            emitter.completeWithError(
                    exception
            );
        }
    }

    private void sendVideoSummaryResult(
            SseEmitter emitter,
            VideoSummaryResult videoSummaryResult,
            AtomicBoolean terminated
    ) {
        if (videoSummaryResult == null) {
            return;
        }

        try {
            emitter.send(
                    SseEmitter.event()
                            .name("video_result")
                            .data(
                                    objectMapper.writeValueAsString(
                                            videoSummaryResult
                                    )
                            )
            );

            log.info(
                    "영상 요약 결과 SSE 전송 완료. fileId={}, fileName={}, durationSeconds={}",
                    videoSummaryResult.fileId(),
                    videoSummaryResult.fileName(),
                    videoSummaryResult.durationSeconds()
            );
        } catch (
                AsyncRequestNotUsableException exception
        ) {
            terminated.set(
                    true
            );

            log.info(
                    "영상 요약 결과 전송 전에 클라이언트 연결이 종료되었습니다. fileId={}",
                    videoSummaryResult.fileId()
            );
        } catch (IOException exception) {
            terminated.set(
                    true
            );

            log.info(
                    "영상 요약 결과 SSE 전송 중 연결이 종료되었습니다. fileId={}",
                    videoSummaryResult.fileId()
            );
        } catch (Exception exception) {
            terminated.set(
                    true
            );

            log.error(
                    "영상 요약 결과 SSE 전송 중 오류가 발생했습니다. fileId={}",
                    videoSummaryResult.fileId(),
                    exception
            );

            emitter.completeWithError(
                    exception
            );
        }
    }

    private void configureEmitterCallbacks(
            SseEmitter emitter,
            Disposable disposable,
            ChatRequest request,
            String roomId,
            StringBuilder assistantContent,
            AtomicBoolean completed,
            AtomicBoolean terminated,
            AtomicBoolean responseSaved
    ) {
        emitter.onCompletion(() -> {
            if (
                    !completed.get()
                            && terminated.compareAndSet(
                            false,
                            true
                    )
            ) {
                saveInterruptedResponse(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            dispose(
                    disposable
            );
        });

        emitter.onTimeout(() -> {
            if (
                    terminated.compareAndSet(
                            false,
                            true
                    )
            ) {
                saveInterruptedResponse(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            completed.compareAndSet(
                    false,
                    true
            );

            log.warn(
                    "SSE 스트리밍 요청 시간이 초과되었습니다. roomId={}",
                    roomId
            );

            dispose(
                    disposable
            );

            emitter.complete();
        });

        emitter.onError(error -> {
            if (
                    terminated.compareAndSet(
                            false,
                            true
                    )
            ) {
                saveInterruptedResponse(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            dispose(
                    disposable
            );

            if (
                    error instanceof AsyncRequestNotUsableException
                            || error instanceof IOException
            ) {
                log.info(
                        "클라이언트 연결 종료로 SSE 오류가 발생했습니다. roomId={}",
                        roomId
                );

                return;
            }

            log.error(
                    "SSE 스트리밍 오류가 발생했습니다. roomId={}",
                    roomId,
                    error
            );
        });
    }

    private void saveInterruptedResponse(
            ChatRequest request,
            String roomId,
            StringBuilder assistantContent,
            AtomicBoolean responseSaved
    ) {
        chatPersistenceService.saveInterruptedAssistantMessage(
                request,
                roomId,
                assistantContent,
                responseSaved
        );
    }

    private void dispose(
            Disposable disposable
    ) {
        if (!disposable.isDisposed()) {
            disposable.dispose();
        }
    }
}