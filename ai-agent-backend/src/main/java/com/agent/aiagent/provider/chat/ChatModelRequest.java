package com.agent.aiagent.provider.chat;

import java.util.List;

public record ChatModelRequest(
        ChatModelType modelType,
        List<ChatModelMessage> messages
) {
}