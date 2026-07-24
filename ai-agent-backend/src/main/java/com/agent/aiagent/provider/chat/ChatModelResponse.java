package com.agent.aiagent.provider.chat;

public record ChatModelResponse(
        String content,
        boolean done
) {
}