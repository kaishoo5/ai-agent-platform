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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GeminiClient {

    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Duration VIDEO_ANALYSIS_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_RATE_LIMIT_RETRIES = 2;
    private static final long DEFAULT_RETRY_DELAY_SECONDS = 35;
    private static final long MAX_RETRY_DELAY_SECONDS = 120;
    private static final Pattern RETRY_DELAY_PATTERN =
            Pattern.compile("retryDelay[^0-9]{1,30}(\\d+)s");

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

    /** Gemini Files API에서 받은 URI를 사용하여 MP4 내용을 분석한다. */
    public JsonNode analyzeVideo(String fileUri, String mimeType, String prompt) {
        if (fileUri == null || !fileUri.startsWith("https://generativelanguage.googleapis.com/")) {
            throw new IllegalArgumentException("올바른 Gemini Files URI가 아닙니다.");
        }
        if (!"video/mp4".equalsIgnoreCase(mimeType)) {
            throw new IllegalArgumentException("현재는 video/mp4만 지원합니다.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("영상 분석 질문이 비어 있습니다.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(
                        Map.of("fileData", Map.of("mimeType", mimeType, "fileUri", fileUri)),
                        Map.of("text", prompt)
                )
        )));
        body.put("generationConfig", Map.of("maxOutputTokens", 8192));
        return executeGenerateContent(body, VIDEO_ANALYSIS_TIMEOUT, false);
    }

    /** Gemini Files API 영상의 지정 구간만 분석한다. 시간 단위는 초다. */
    public JsonNode analyzeVideoSegment(
            String fileUri, String mimeType, String prompt,
            long startSeconds, long endSeconds
    ) {
        if (fileUri == null || !fileUri.startsWith("https://generativelanguage.googleapis.com/")) {
            throw new IllegalArgumentException("올바른 Gemini Files URI가 아닙니다.");
        }
        if (!"video/mp4".equalsIgnoreCase(mimeType)) {
            throw new IllegalArgumentException("현재는 video/mp4만 지원합니다.");
        }
        if (prompt == null || prompt.isBlank() || startSeconds < 0 || endSeconds <= startSeconds) {
            throw new IllegalArgumentException("영상 분석 구간 또는 프롬프트가 올바르지 않습니다.");
        }

        Map<String, Object> fileData = Map.of(
                "mimeType", mimeType,
                "fileUri", fileUri
        );
        Map<String, Object> videoMetadata = Map.of(
                "startOffset", startSeconds + "s",
                "endOffset", endSeconds + "s"
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(
                        Map.of("fileData", fileData, "videoMetadata", videoMetadata),
                        Map.of("text", prompt)
                )
        )));
        body.put("generationConfig", Map.of("maxOutputTokens", 8192));
        return executeGenerateContent(body, VIDEO_ANALYSIS_TIMEOUT, false);
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
        return executeGenerateContent(requestBody, REQUEST_TIMEOUT, true);
    }

    private JsonNode executeGenerateContent(
            Map<String, Object> requestBody,
            Duration timeout,
            boolean retryOnRateLimit
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY 환경변수가 설정되지 않았습니다.");
        }

        int maxAttempts = retryOnRateLimit ? MAX_RATE_LIMIT_RETRIES + 1 : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeGenerateContentOnce(requestBody, timeout);
            } catch (GeminiRateLimitException e) {
                if (attempt >= maxAttempts) {
                    log.warn("Gemini 호출 제한으로 최종 실패. model={}, attempts={}", model, attempt);
                    throw e;
                }

                long delaySeconds = e.retryDelaySeconds();
                log.warn("Gemini 429 호출 제한. model={}, attempt={}/{}, retryAfter={}s",
                        model, attempt, maxAttempts, delaySeconds);

                try {
                    Thread.sleep(Duration.ofSeconds(delaySeconds));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Gemini 재시도 대기 중 작업이 중단되었습니다.", interrupted);
                }
            }
        }

        throw new IllegalStateException("Gemini API 재시도 횟수를 초과했습니다.");
    }

    private JsonNode executeGenerateContentOnce(
            Map<String, Object> requestBody,
            Duration timeout
    ) {
        log.info("Gemini API 호출 시작. model={}, toolsEnabled={}",
                model, requestBody.containsKey("tools"));

        try {
            String responseBody = webClient.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            response -> response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        int status = response.statusCode().value();
                                        if (status == 429) {
                                            return Mono.error(new GeminiRateLimitException(
                                                    body, parseRetryDelaySeconds(body)));
                                        }
                                        return Mono.error(new IllegalStateException(
                                                "Gemini API 오류. HTTP " + status + ", body=" + body));
                                    })
                    )
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("Gemini API 응답이 비어 있습니다.");
            }

            JsonNode result = objectMapper.readTree(responseBody);
            JsonNode usage = result.path("usageMetadata");
            log.info("Gemini API 호출 완료. model={}, promptTokens={}, outputTokens={}, totalTokens={}",
                    model,
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    usage.path("totalTokenCount").asInt(0));
            return result;
        } catch (WebClientRequestException e) {
            log.error("Gemini 연결 실패. message={}", e.getMessage());
            throw new IllegalStateException("Gemini API 서버에 연결하지 못했습니다.", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            throw new IllegalStateException("Gemini API 호출 중 오류가 발생했습니다.", e);
        }
    }

    private long parseRetryDelaySeconds(String responseBody) {
        if (responseBody != null) {
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(responseBody);
            if (matcher.find()) {
                try {
                    long seconds = Long.parseLong(matcher.group(1));
                    return Math.max(1, Math.min(seconds + 2, MAX_RETRY_DELAY_SECONDS));
                } catch (NumberFormatException ignored) {
                    // Gemini 응답이 예상과 다르면 기본 대기 시간을 사용한다.
                }
            }
        }
        return DEFAULT_RETRY_DELAY_SECONDS;
    }

    public String getModel() {
        return model;
    }

    private static final class GeminiRateLimitException extends IllegalStateException {
        private final long retryDelaySeconds;

        private GeminiRateLimitException(String responseBody, long retryDelaySeconds) {
            super("Gemini API 오류. HTTP 429, body=" + responseBody);
            this.retryDelaySeconds = retryDelaySeconds;
        }

        private long retryDelaySeconds() {
            return retryDelaySeconds;
        }
    }
}
