package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.domain.chat.dto.ChatMessageRequest;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.infra.ollama.dto.OllamaChatRequest;
import com.agent.aiagent.infra.ollama.dto.OllamaChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OllamaClient {

    private static final String MODEL_NAME = "qwen3:4b";

    private final WebClient ollamaWebClient;

    public Flux<OllamaChatResponse> chat(
            List<ChatMessageRequest> messages
    ) {
        List<OllamaChatMessage> ollamaMessages = messages.stream()
                .map(message ->
                        new OllamaChatMessage(
                                message.getRole(),
                                message.getContent()
                        )
                )
                .toList();

        OllamaChatRequest request = OllamaChatRequest.builder()
                .model(MODEL_NAME)
                .messages(ollamaMessages)
                .stream(true)
                .build();

        return ollamaWebClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(OllamaChatResponse.class);
    }

}