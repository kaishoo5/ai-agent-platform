package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ChatStreamService {

    private static final long SSE_TIMEOUT = 60_000L;

    private final ObjectMapper objectMapper;

    public ChatStreamService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CompletableFuture.runAsync(() -> {
            String responseMessage = String.format(
                    "\"%s\" 메시지를 스트리밍 방식으로 정상적으로 받았습니다.",
                    request.getMessage()
            );

            try {
                for (int index = 0; index < responseMessage.length(); index++) {
                    String chunk = String.valueOf(
                            responseMessage.charAt(index)
                    );

                    log.info("chunk: {}", chunk);

                    emitter.send(
                            SseEmitter.event()
                                    .name("message")
                                    .data(
                                            objectMapper.writeValueAsString(chunk)
                                    )
                    );

                    Thread.sleep(60L);
                }

                emitter.send(
                        SseEmitter.event()
                                .name("done")
                                .data("")
                );

                emitter.complete();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(exception);
            } catch (IOException exception) {
                emitter.completeWithError(exception);
            }
        });

        emitter.onTimeout(emitter::complete);

        emitter.onError(error ->
                log.error("SSE streaming error", error)
        );

        return emitter;
    }

}