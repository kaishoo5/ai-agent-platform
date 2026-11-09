package com.agent.aiagent.infra.gemini;

import com.agent.aiagent.infra.provider.gemini.GeminiToolMapper;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelTool;
import com.agent.aiagent.provider.chat.ChatModelToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiClient {

    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(60);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final GeminiToolMapper geminiToolMapper;
    private final String apiKey;
    private final String model;

    public GeminiClient(
            ObjectMapper objectMapper,
            GeminiToolMapper geminiToolMapper,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.api.base-url}") String baseUrl,
            @Value("${gemini.api.model}") String model
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.objectMapper = objectMapper;
        this.geminiToolMapper = geminiToolMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    public JsonNode generateContent(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException(
                    "Gemini 프롬프트가 비어 있습니다."
            );
        }

        return generateContent(
                List.of(new ChatModelMessage("user", prompt)),
                List.of()
        );
    }

    public JsonNode generateContent(
            List<ChatModelMessage> messages
    ) {
        return generateContent(messages, List.of());
    }

    public JsonNode generateContent(
            List<ChatModelMessage> messages,
            List<ChatModelTool> tools
    ) {
        return executeGenerateContent(createRequestBody(messages, tools));
    }

    public Flux<JsonNode> streamGenerateContent(
            List<ChatModelMessage> messages
    ) {
        return streamGenerateContent(messages, List.of());
    }

    public Flux<JsonNode> streamGenerateContent(
            List<ChatModelMessage> messages,
            List<ChatModelTool> tools
    ) {
        return Flux.defer(() -> {
            Map<String, Object> requestBody = createRequestBody(messages, tools);

            if (apiKey == null || apiKey.isBlank()) {
                return Flux.error(new IllegalStateException(
                        "GEMINI_API_KEY 환경변수가 설정되지 않았습니다."
                ));
            }

            log.info("Gemini 스트리밍 API 호출 시작. model={}, toolsEnabled={}",
                    model, requestBody.containsKey("tools"));

            return webClient.post()
                    .uri("/v1beta/models/{model}:streamGenerateContent?alt=sse", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            response -> response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new IllegalStateException(
                                            "Gemini 스트리밍 API 오류. HTTP "
                                                    + response.statusCode().value()
                                                    + ", body=" + body
                                    )))
                    )
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .mapNotNull(event -> {
                        String data = event.data();
                        if (data == null || data.isBlank()) {
                            return null;
                        }
                        return objectMapper.readTree(data);
                    })
                    .timeout(REQUEST_TIMEOUT)
                    .doOnComplete(() -> log.info("Gemini 스트리밍 API 완료. model={}", model))
                    .doOnError(error -> log.error("Gemini 스트리밍 API 오류. model={}", model, error));
        });
    }

    private Map<String, Object> createRequestBody(
            List<ChatModelMessage> messages,
            List<ChatModelTool> tools
    ) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "Gemini 대화 메시지가 비어 있습니다."
            );
        }

        List<Map<String, Object>> contents =
                new ArrayList<>();

        StringBuilder systemInstruction =
                new StringBuilder();

        for (ChatModelMessage message : messages) {
            if (message == null) {
                continue;
            }

            String role = message.getRole();

            if (role == null || role.isBlank()) {
                continue;
            }

            if ("system".equalsIgnoreCase(role)) {
                String content = message.getContent();

                if (content != null && !content.isBlank()) {
                    if (!systemInstruction.isEmpty()) {
                        systemInstruction.append("\n\n");
                    }

                    systemInstruction.append(content);
                }

                continue;
            }

            if ("tool".equalsIgnoreCase(role)) {
                contents.add(createToolResponse(message));
                continue;
            }

            contents.add(createConversationMessage(message));
        }

        if (contents.isEmpty()) {
            throw new IllegalArgumentException(
                    "Gemini에 전달할 대화 메시지가 없습니다."
            );
        }

        Map<String, Object> requestBody =
                new LinkedHashMap<>();

        requestBody.put("contents", contents);

        if (!systemInstruction.isEmpty()) {
            requestBody.put(
                    "systemInstruction",
                    Map.of(
                            "parts",
                            List.of(
                                    Map.of(
                                            "text",
                                            systemInstruction.toString()
                                    )
                            )
                    )
            );
        }

        requestBody.put(
                "generationConfig",
                Map.of("maxOutputTokens", 2048)
        );

        if (tools != null && !tools.isEmpty()) {
            requestBody.put(
                    "tools",
                    geminiToolMapper.map(tools)
            );
        }

        return requestBody;
    }


    private Map<String, Object> createConversationMessage(
            ChatModelMessage message
    ) {
        String role = message.getRole();

        String geminiRole;

        if ("user".equalsIgnoreCase(role)) {
            geminiRole = "user";
        } else if ("assistant".equalsIgnoreCase(role)
                || "model".equalsIgnoreCase(role)) {
            geminiRole = "model";
        } else {
            throw new UnsupportedOperationException(
                    "지원하지 않는 Gemini 메시지 역할: " + role
            );
        }

        List<Map<String, Object>> parts =
                new ArrayList<>();

        String content = message.getContent();

        if (content != null && !content.isBlank()) {
            parts.add(
                    Map.of("text", content)
            );
        }

        List<ChatModelToolCall> toolCalls =
                message.getToolCalls();

        if (toolCalls != null && !toolCalls.isEmpty()) {
            if (!"model".equals(geminiRole)) {
                throw new IllegalArgumentException(
                        "Tool 호출은 assistant 메시지에만 포함할 수 있습니다."
                );
            }

            for (ChatModelToolCall toolCall : toolCalls) {
                Map<String, Object> functionCall =
                        new LinkedHashMap<>();

                functionCall.put(
                        "name",
                        toolCall.name()
                );

                functionCall.put(
                        "args",
                        toolCall.arguments()
                );

                Map<String, Object> part =
                        new LinkedHashMap<>();

                part.put(
                        "functionCall",
                        functionCall
                );

                String thoughtSignature =
                        toolCall.thoughtSignature();

                if (thoughtSignature != null
                        && !thoughtSignature.isBlank()) {
                    part.put(
                            "thoughtSignature",
                            thoughtSignature
                    );
                }

                parts.add(part);
            }
        }

        if (message.getImages() != null
                && !message.getImages().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Gemini 이미지 입력은 아직 지원하지 않습니다."
            );
        }

        if (parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Gemini 메시지 내용이 비어 있습니다. role=" + role
            );
        }

        return Map.of(
                "role", geminiRole,
                "parts", parts
        );
    }

    private Map<String, Object> createToolResponse(
            ChatModelMessage message
    ) {
        String toolName = message.getToolName();

        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "Gemini Tool 응답에 toolName이 없습니다."
            );
        }

        String content = message.getContent();

        Map<String, Object> response =
                new LinkedHashMap<>();

        response.put(
                "result",
                content == null ? "" : content
        );

        return Map.of(
                "role", "user",
                "parts", List.of(
                        Map.of(
                                "functionResponse",
                                Map.of(
                                        "name", toolName,
                                        "response", response
                                )
                        )
                )
        );
    }

    private JsonNode executeGenerateContent(
            Map<String, Object> requestBody
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY 환경변수가 설정되지 않았습니다."
            );
        }

        log.info(
                "Gemini API 호출 시작. model={}, toolsEnabled={}",
                model,
                requestBody.containsKey("tools")
        );

        try {
            String responseBody = webClient.post()
                    .uri(
                            "/v1beta/models/{model}:generateContent",
                            model
                    )
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            response -> response
                                    .bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(
                                            new IllegalStateException(
                                                    "Gemini API 오류. HTTP "
                                                            + response.statusCode().value()
                                                            + ", body="
                                                            + body
                                            )
                                    ))
                    )
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException(
                        "Gemini API 응답이 비어 있습니다."
                );
            }

            JsonNode result =
                    objectMapper.readTree(responseBody);

            JsonNode usage =
                    result.path("usageMetadata");

            log.info(
                    "Gemini API 호출 완료. model={}, "
                            + "promptTokens={}, outputTokens={}, totalTokens={}",
                    model,
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    usage.path("totalTokenCount").asInt(0)
            );

            return result;

        } catch (WebClientRequestException e) {
            log.error(
                    "Gemini 연결 실패. message={}",
                    e.getMessage()
            );

            throw new IllegalStateException(
                    "Gemini API 서버에 연결하지 못했습니다.",
                    e
            );

        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }

            throw new IllegalStateException(
                    "Gemini API 호출 중 오류가 발생했습니다.",
                    e
            );
        }
    }

    public String getModel() {
        return model;
    }
}
