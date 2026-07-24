package com.agent.aiagent.provider.chat;

import java.util.Map;

public record ChatModelToolCall(
        String name,
        Map<String, Object> arguments
) {
    public ChatModelToolCall {
        arguments = arguments == null
                ? Map.of()
                : Map.copyOf(arguments);
    }
}