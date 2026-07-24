package com.agent.aiagent.provider.chat;

import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;

import java.util.List;

public record ChatModelRequest(
        ChatModelType modelType,
        List<OllamaChatMessage> messages
) {
}