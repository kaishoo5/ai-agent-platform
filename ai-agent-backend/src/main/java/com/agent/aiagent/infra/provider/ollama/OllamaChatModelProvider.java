package com.agent.aiagent.infra.provider.ollama;

import com.agent.aiagent.infra.ollama.OllamaClient;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.infra.ollama.dto.OllamaChatResponse;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OllamaChatModelProvider
        implements ChatModelProvider {

    private final OllamaClient ollamaClient;

    @Override
    public String chatOnce(
            ChatModelType modelType,
            List<ChatModelMessage> messages
    ) {
        return ollamaClient.chatOnce(
                resolveModel(modelType),
                toOllamaMessages(messages)
        );
    }

    @Override
    public Flux<OllamaChatResponse> chat(
            ChatModelType modelType,
            List<ChatModelMessage> messages
    ) {
        return ollamaClient.chat(
                resolveModel(modelType),
                toOllamaMessages(messages)
        );
    }

    private List<OllamaChatMessage> toOllamaMessages(
            List<ChatModelMessage> messages
    ) {
        return messages.stream()
                .map(message ->
                        new OllamaChatMessage(
                                message.getRole(),
                                message.getContent(),
                                message.getImages()
                        )
                )
                .toList();
    }

    private String resolveModel(ChatModelType modelType) {
        return switch (modelType) {
            case TEXT -> OllamaClient.MODEL_TEXT;
            case VISION -> OllamaClient.MODEL_VISION;
        };
    }
}
