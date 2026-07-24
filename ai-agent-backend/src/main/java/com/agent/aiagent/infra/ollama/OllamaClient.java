package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.infra.ollama.dto.*;
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

    public static final String MODEL_EMBEDDING =
            "embeddinggemma";

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

    public List<List<Double>> embed(
            List<String> inputs
    ) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        OllamaEmbeddingRequest request =
                new OllamaEmbeddingRequest(
                        MODEL_EMBEDDING,
                        inputs
                );

        OllamaEmbeddingResponse response =
                ollamaWebClient.post()
                        .uri("/api/embed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .onStatus(
                                status -> status.isError(),
                                clientResponse ->
                                        clientResponse.bodyToMono(
                                                        String.class
                                                )
                                                .defaultIfEmpty("")
                                                .flatMap(errorBody -> {
                                                    log.error(
                                                            "Ollama embedding 호출 실패. status={}, model={}, body={}",
                                                            clientResponse.statusCode(),
                                                            MODEL_EMBEDDING,
                                                            errorBody
                                                    );

                                                    return clientResponse.createException();
                                                })
                        )
                        .bodyToMono(
                                OllamaEmbeddingResponse.class
                        )
                        .block();

        if (
                response == null
                        || response.getEmbeddings() == null
                        || response.getEmbeddings().isEmpty()
        ) {
            throw new IllegalStateException(
                    "Ollama embedding 응답이 비어 있습니다."
            );
        }

        return response.getEmbeddings();
    }

    public String chatOnce(
            String model,
            List<OllamaChatMessage> messages
    ) {
        OllamaChatRequest request =
                OllamaRequestBuilder.build(
                        model,
                        messages,
                        false
                );

        OllamaChatResponse response =
                ollamaWebClient.post()
                        .uri("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(OllamaChatResponse.class)
                        .block();

        if (
                response == null
                        || response.getMessage() == null
                        || response.getMessage().getContent() == null
        ) {
            throw new IllegalStateException(
                    "Ollama chat 응답이 비어 있습니다."
            );
        }

        return response.getMessage().getContent();
    }

}