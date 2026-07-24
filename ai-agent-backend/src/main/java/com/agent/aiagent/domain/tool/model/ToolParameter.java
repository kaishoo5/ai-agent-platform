package com.agent.aiagent.domain.tool.model;

public record ToolParameter(
        String type,
        String description,
        boolean required
) {
}