package com.agent.aiagent.provider.chat;

import java.util.Map;

public record ChatModelTool(
        String name,
        String description,
        Map<String, ChatModelToolParameter> parameters
) {

    public ChatModelTool {
        parameters =
                parameters == null
                        ? Map.of()
                        : Map.copyOf(
                        parameters
                );
    }
}