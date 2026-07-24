package com.agent.aiagent.provider.chat;

import java.util.List;

public record ChatModelResponse(
        String content,
        boolean done,
        List<ChatModelToolCall> toolCalls
) {
    public ChatModelResponse {
        toolCalls = toolCalls == null
                ? List.of()
                : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}