package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.infra.ollama.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final long SSE_TIMEOUT = 300_000L;

    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        ollamaClient.generate(request.getMessage())
                .subscribe(
                        response -> {
                            String chunk = response.getResponse();

                            if (chunk == null || chunk.isEmpty()) {
                                return;
                            }

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
                            } catch (Exception exception) {
                                log.error(
                                        "Ollama 응답 전송 중 오류가 발생했습니다.",
                                        exception
                                );

                                emitter.completeWithError(exception);
                            }
                        },
                        error -> {
                            log.error(
                                    "Ollama 스트리밍 호출 중 오류가 발생했습니다.",
                                    error
                            );

                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name("done")
                                                .data("")
                                );

                                emitter.complete();
                            } catch (IOException exception) {
                                emitter.completeWithError(exception);
                            }
                        }
                );

        emitter.onTimeout(() -> {
            log.warn("SSE 스트리밍 요청 시간이 초과되었습니다.");
            emitter.complete();
        });

        emitter.onError(error ->
                log.error("SSE 스트리밍 오류가 발생했습니다.", error)
        );

        return emitter;
    }

}