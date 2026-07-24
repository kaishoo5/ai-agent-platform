package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
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

        Disposable disposable = chatModelProvider
                .chat(
                        chatModelRequest.modelType(),
                        chatModelRequest.messages()
                )
                .subscribe(
                        response -> {
                            if (terminated.get()) {
                                return;
                            }

                            if (response.getMessage() == null) {
                                return;
                            }

                            String chunk = response
                                    .getMessage()
                                    .getContent();

                            if (chunk == null || chunk.isEmpty()) {
                                return;
                            }

                            assistantContent.append(chunk);

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
                            } catch (AsyncRequestNotUsableException exception) {
                                if (terminated.compareAndSet(false, true)) {
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
                                if (terminated.compareAndSet(false, true)) {
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
                                terminated.set(true);

                                log.error(
                                        "AI 응답 전송 중 오류가 발생했습니다. roomId={}",
                                        roomId,
                                        exception
                                );

                                emitter.completeWithError(exception);
                            }
                        },
                        error -> {
                            if (
                                    terminated.get()
                                            || !completed.compareAndSet(false, true)
                            ) {
                                return;
                            }

                            log.error(
                                    "AI 스트리밍 호출 중 오류가 발생했습니다. roomId={}",
                                    roomId,
                                    error
                            );

                            emitter.completeWithError(error);
                        },
                        () -> {
                            if (
                                    terminated.get()
                                            || !completed.compareAndSet(false, true)
                            ) {
                                return;
                            }

                            try {
                                if (responseSaved.compareAndSet(false, true)) {
                                    if (request.isRegenerate()) {
                                        chatPersistenceService
                                                .replaceLastAssistantMessage(
                                                        roomId,
                                                        assistantContent.toString()
                                                );
                                    } else {
                                        chatPersistenceService
                                                .saveAssistantMessage(
                                                        roomId,
                                                        assistantContent.toString()
                                                );
                                    }
                                }

                                emitter.send(
                                        SseEmitter.event()
                                                .name("done")
                                                .data("")
                                );

                                emitter.complete();
                            } catch (AsyncRequestNotUsableException exception) {
                                terminated.set(true);

                                log.info(
                                        "완료 응답 전송 전에 클라이언트 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (IOException exception) {
                                terminated.set(true);

                                log.info(
                                        "완료 응답 전송 중 연결이 종료되었습니다. roomId={}",
                                        roomId
                                );
                            } catch (Exception exception) {
                                terminated.set(true);

                                log.error(
                                        "AI 응답 저장 또는 SSE 완료 처리 중 오류가 발생했습니다. roomId={}",
                                        roomId,
                                        exception
                                );

                                emitter.completeWithError(exception);
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
                            && terminated.compareAndSet(false, true)
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
            if (terminated.compareAndSet(false, true)) {
                saveInterruptedResponse(
                        request,
                        roomId,
                        assistantContent,
                        responseSaved
                );
            }

            completed.compareAndSet(false, true);

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
            if (terminated.compareAndSet(false, true)) {
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