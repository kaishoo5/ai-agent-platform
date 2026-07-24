package com.agent.aiagent.provider.chat;

public record ChatModelToolParameter(
        String type,
        String description,
        boolean required
) {
}