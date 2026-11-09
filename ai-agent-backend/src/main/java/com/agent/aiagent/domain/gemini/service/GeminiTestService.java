package com.agent.aiagent.domain.gemini.service;

import com.agent.aiagent.infra.gemini.GeminiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class GeminiTestService {

    private final GeminiClient geminiClient;

    public GeminiTestResponse test() {

        String prompt = """
                다음 질문에 한국어로 간단히 답변하세요.

                질문: Gemini API 연결 테스트입니다.
                현재 정상적으로 응답하고 있는지 알려주세요.
                """;

        JsonNode result = geminiClient.generateContent(prompt);

        StringBuilder content = new StringBuilder();

        JsonNode candidates = result.path("candidates");

        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {

                JsonNode parts = candidate
                        .path("content")
                        .path("parts");

                if (!parts.isArray()) {
                    continue;
                }

                for (JsonNode part : parts) {
                    String text = part.path("text").asText("");

                    if (!text.isBlank()) {
                        content.append(text);
                    }
                }
            }
        }

        JsonNode usage = result.path("usageMetadata");

        return new GeminiTestResponse(
                geminiClient.getModel(),
                content.toString(),
                usage.path("promptTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("totalTokenCount").asInt(0),
                result.path("modelVersion").asText(""),
                result.path("responseId").asText("")
        );
    }

    public record GeminiTestResponse(
            String model,
            String content,
            int promptTokens,
            int outputTokens,
            int totalTokens,
            String modelVersion,
            String responseId
    ) {
    }
}
