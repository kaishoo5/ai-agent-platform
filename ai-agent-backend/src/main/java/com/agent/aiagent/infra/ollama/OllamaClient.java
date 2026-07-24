package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.infra.ollama.dto.OllamaChatRequest;
import com.agent.aiagent.infra.ollama.dto.OllamaChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    public static final String MODEL_TEXT =
            "qwen3:4b";

    public static final String MODEL_VISION =
            "qwen3-vl:4b";

    private final WebClient ollamaWebClient;

    public Flux<OllamaChatResponse> chat(
            String model,
            List<OllamaChatMessage> messages
    ) {

        OllamaChatRequest request =
                OllamaRequestBuilder.build(
                        model,
                        messages
                );

        return ollamaWebClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        response ->
                                response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(errorBody -> {
                                            log.error(
                                                    "Ollama API 오류. status={}, model={}, body={}",
                                                    response.statusCode(),
                                                    model,
                                                    errorBody
                                            );

                                            return response.createException();
                                        })
                )
                .bodyToFlux(OllamaChatResponse.class);
    }

}