package com.agent.aiagent.domain.tool.model;

public record ToolResult(
        boolean success,
        String content
) {

    public static ToolResult success(
            String content
    ) {
        return new ToolResult(
                true,
                content
        );
    }

    public static ToolResult failure(
            String content
    ) {
        return new ToolResult(
                false,
                content
        );
    }
}