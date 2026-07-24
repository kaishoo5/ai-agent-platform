package com.agent.aiagent.provider.chat;

import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;

import java.util.List;

public interface ChatModelProvider {

    String chatOnce(
            String model,
            List<OllamaChatMessage> messages
    );
}
