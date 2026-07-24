package com.agent.aiagent.infra.provider.ollama;

import com.agent.aiagent.infra.ollama.OllamaClient;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OllamaChatModelProvider
        implements ChatModelProvider {

    private final OllamaClient ollamaClient;

    @Override
    public String chatOnce(
            String model,
            List<OllamaChatMessage> messages
    ) {
        return ollamaClient.chatOnce(
                model,
                messages
        );
    }
}
