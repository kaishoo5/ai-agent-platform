package com.agent.aiagent.infra.provider.gemini;

import com.agent.aiagent.infra.gemini.GeminiClient;
import com.agent.aiagent.provider.chat.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiChatModelProvider implements ChatModelProvider {

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public GeminiChatModelProvider(
            GeminiClient geminiClient,
            ObjectMapper objectMapper
    ) {
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatModelResponse chatOnce(
            ChatModelRequest request
    ) {
        validateRequest(request);

        JsonNode response = geminiClient.generateContent(
                request.messages(),
                request.tools()
        );

        JsonNode candidates =
                response.path("candidates");

        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Gemini 응답에 candidates가 없습니다."
            );
        }

        JsonNode candidate = candidates.get(0);

        JsonNode parts = candidate
                .path("content")
                .path("parts");

        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException(
                    "Gemini 응답에 parts가 없습니다. finishReason="
                            + candidate.path("finishReason").asText("")
            );
        }

        StringBuilder content =
                new StringBuilder();

        List<ChatModelToolCall> toolCalls =
                new ArrayList<>();

        for (JsonNode part : parts) {
            JsonNode textNode = part.path("text");

            if (!textNode.isMissingNode()
                    && !textNode.isNull()) {
                content.append(textNode.asText(""));
            }

            JsonNode functionCall =
                    part.path("functionCall");

            if (functionCall.isObject()) {
                toolCalls.add(
                        toChatModelToolCall(part)
                );
            }
        }

        String answer = content.toString();

        if (answer.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalStateException(
                    "Gemini 응답에 텍스트와 Tool 호출이 모두 없습니다. "
                            + "finishReason="
                            + candidate.path("finishReason").asText("")
            );
        }

        log.info(
                "Gemini 응답 변환 완료. contentLength={}, toolCallCount={}",
                answer.length(),
                toolCalls.size()
        );

        return new ChatModelResponse(
                answer,
                true,
                toolCalls
        );
    }

    @Override
    public Flux<ChatModelResponse> chat(
            ChatModelRequest request
    ) {
        return Flux.defer(() -> {
            validateRequest(request);

            return geminiClient.streamGenerateContent(
                            request.messages(),
                            request.tools()
                    )
                    .map(this::toStreamingResponse)
                    .filter(response ->
                            (response.content() != null && !response.content().isEmpty())
                                    || response.hasToolCalls()
                    );
        });
    }

    private ChatModelResponse toStreamingResponse(
            JsonNode response
    ) {
        JsonNode candidates = response.path("candidates");

        if (!candidates.isArray() || candidates.isEmpty()) {
            return new ChatModelResponse("", false, List.of());
        }

        JsonNode candidate = candidates.get(0);
        JsonNode parts = candidate.path("content").path("parts");
        StringBuilder content = new StringBuilder();
        List<ChatModelToolCall> toolCalls = new ArrayList<>();

        if (parts.isArray()) {
            for (JsonNode part : parts) {
                JsonNode textNode = part.path("text");

                if (textNode.isTextual()) {
                    content.append(textNode.asText());
                }

                if (part.path("functionCall").isObject()) {
                    toolCalls.add(toChatModelToolCall(part));
                }
            }
        }

        String finishReason = candidate.path("finishReason").asText("");
        boolean done = !finishReason.isBlank();

        return new ChatModelResponse(
                content.toString(),
                done,
                toolCalls
        );
    }


    private ChatModelToolCall toChatModelToolCall(
            JsonNode part
    ) {
        JsonNode functionCall =
                part.path("functionCall");

        String name = functionCall
                .path("name")
                .asText("");

        if (name.isBlank()) {
            throw new IllegalStateException(
                    "Gemini functionCall에 name이 없습니다."
            );
        }

        JsonNode argsNode =
                functionCall.path("args");

        Map<String, Object> arguments;

        if (argsNode.isObject()) {
            arguments = objectMapper.convertValue(
                    argsNode,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
        } else if (argsNode.isMissingNode()
                || argsNode.isNull()) {
            arguments = Map.of();
        } else {
            throw new IllegalStateException(
                    "Gemini functionCall.args가 객체가 아닙니다. tool="
                            + name
            );
        }

        JsonNode signatureNode =
                part.path("thoughtSignature");

        String thoughtSignature =
                signatureNode.isTextual()
                        ? signatureNode.asText()
                        : null;

        log.info(
                "Gemini Tool 호출 감지. tool={}, argumentKeys={}, hasThoughtSignature={}",
                name,
                arguments.keySet(),
                thoughtSignature != null
                        && !thoughtSignature.isBlank()
        );

        return new ChatModelToolCall(
                name,
                arguments,
                thoughtSignature
        );
    }

    private void validateRequest(
            ChatModelRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Gemini 요청이 비어 있습니다."
            );
        }

        if (request.messages() == null
                || request.messages().isEmpty()) {
            throw new IllegalArgumentException(
                    "Gemini 대화 메시지가 비어 있습니다."
            );
        }

        for (ChatModelMessage message : request.messages()) {
            if (message == null) {
                continue;
            }

            if (message.getImages() != null
                    && !message.getImages().isEmpty()) {
                throw new UnsupportedOperationException(
                        "Gemini 이미지 입력은 아직 연결되지 않았습니다."
                );
            }

            String role = message.getRole();

            if (role == null
                    || (!"system".equalsIgnoreCase(role)
                    && !"user".equalsIgnoreCase(role)
                    && !"assistant".equalsIgnoreCase(role)
                    && !"model".equalsIgnoreCase(role)
                    && !"tool".equalsIgnoreCase(role))) {
                throw new UnsupportedOperationException(
                        "지원하지 않는 Gemini 메시지 역할: " + role
                );
            }
        }
    }
}
