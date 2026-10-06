package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.infra.provider.ollama.dto.*;
import com.agent.aiagent.settings.service.ModelSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    private static final String TOOL_CALL_PARSE_ERROR =
            "error parsing tool call";

    private static final int CHAT_ONCE_MAX_ATTEMPTS =
            2;

    private final WebClient ollamaWebClient;
    private final ModelSettingsService modelSettingsService;

    public Flux<OllamaChatResponse> chat(
            String model,
            List<OllamaChatMessage> messages
    ) {
        return chat(
                model,
                messages,
                List.of()
        );
    }

    public Flux<OllamaChatResponse> chat(
            String model,
            List<OllamaChatMessage> messages,
            List<OllamaTool> tools
    ) {
        OllamaChatRequest request =
                OllamaRequestBuilder.build(
                        model,
                        messages,
                        tools
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
                .bodyToFlux(
                        OllamaChatResponse.class
                );
    }

    public List<List<Double>> embed(
            List<String> inputs
    ) {
        if (
                inputs == null
                        || inputs.isEmpty()
        ) {
            return List.of();
        }

        OllamaEmbeddingRequest request =
                new OllamaEmbeddingRequest(
                        modelSettingsService.getEmbeddingModel(),
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
                                                            modelSettingsService.getEmbeddingModel(),
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

    public OllamaChatResponse chatOnce(
            String model,
            List<OllamaChatMessage> messages
    ) {
        return chatOnce(
                model,
                messages,
                List.of()
        );
    }

    public OllamaChatResponse chatOnce(
            String model,
            List<OllamaChatMessage> messages,
            List<OllamaTool> tools
    ) {
        OllamaChatRequest request =
                OllamaRequestBuilder.build(
                        model,
                        messages,
                        tools,
                        false
                );

        int toolCount =
                tools == null
                        ? 0
                        : tools.size();

        for (
                int attempt = 1;
                attempt <= CHAT_ONCE_MAX_ATTEMPTS;
                attempt++
        ) {
            try {
                log.info(
                        "Ollama chatOnce 요청. model={}, toolCount={}, attempt={}/{}",
                        model,
                        toolCount,
                        attempt,
                        CHAT_ONCE_MAX_ATTEMPTS
                );

                OllamaChatResponse response =
                        executeChatOnce(
                                model,
                                request
                        );

                log.info(
                        "Ollama chatOnce 응답. content={}",
                        response != null
                                && response.getMessage() != null
                                ? response.getMessage().getContent()
                                : null
                );

                log.info(
                        "Ollama chatOnce toolCalls={}",
                        response != null
                                && response.getMessage() != null
                                ? response.getMessage().getToolCalls()
                                : null
                );

                if (
                        response == null
                                || response.getMessage() == null
                ) {
                    throw new IllegalStateException(
                            "Ollama chat 응답이 비어 있습니다."
                    );
                }

                return response;

            } catch (OllamaChatException exception) {
                boolean toolCallParseError =
                        exception.getResponseBody().contains(
                                TOOL_CALL_PARSE_ERROR
                        );

                if (
                        toolCallParseError
                                && attempt < CHAT_ONCE_MAX_ATTEMPTS
                ) {
                    log.warn(
                            "Ollama Tool Call JSON 파싱 오류가 발생하여 재시도합니다. "
                                    + "model={}, attempt={}/{}, body={}",
                            model,
                            attempt,
                            CHAT_ONCE_MAX_ATTEMPTS,
                            exception.getResponseBody()
                    );

                    continue;
                }

                throw exception;
            }
        }

        throw new IllegalStateException(
                "Ollama chat 호출에 실패했습니다."
        );
    }

    /**
     * 실제 non-streaming Ollama chat API를 한 번 호출한다.
     */
    private OllamaChatResponse executeChatOnce(
            String model,
            OllamaChatRequest request
    ) {
        return ollamaWebClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse ->
                                clientResponse.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(errorBody -> {
                                            log.error(
                                                    "Ollama chat 호출 실패. status={}, model={}, body={}",
                                                    clientResponse.statusCode(),
                                                    model,
                                                    errorBody
                                            );

                                            return reactor.core.publisher.Mono.error(
                                                    new OllamaChatException(
                                                            clientResponse.statusCode().value(),
                                                            errorBody
                                                    )
                                            );
                                        })
                )
                .bodyToMono(
                        OllamaChatResponse.class
                )
                .block();
    }

    /**
     * 모델이 Tool Call arguments를 잘못된 JSON으로 생성하여
     * Ollama가 Tool Call parsing 오류를 반환했는지 확인한다.
     *
     * 이 경우에만 동일 요청을 한 번 재시도한다.
     */
    private boolean isToolCallParseError(
            WebClientResponseException exception
    ) {
        String responseBody =
                exception.getResponseBodyAsString();

        return responseBody != null
                && responseBody.contains(
                TOOL_CALL_PARSE_ERROR
        );
    }

    private static class OllamaChatException
            extends RuntimeException {

        private final int statusCode;
        private final String responseBody;

        private OllamaChatException(
                int statusCode,
                String responseBody
        ) {
            super(
                    "Ollama chat 호출 실패. status="
                            + statusCode
                            + ", body="
                            + responseBody
            );

            this.statusCode = statusCode;
            this.responseBody =
                    responseBody == null
                            ? ""
                            : responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}