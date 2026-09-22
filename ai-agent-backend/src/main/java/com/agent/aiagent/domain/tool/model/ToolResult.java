package com.agent.aiagent.domain.tool.model;

import java.util.Map;

public record ToolResult(
        boolean success,
        String content,
        Map<String, Object> metadata
) {

    public static ToolResult success(
            String content
    ) {
        return new ToolResult(
                true,
                content,
                Map.of()
        );
    }

    public static ToolResult success(
            String content,
            Map<String, Object> metadata
    ) {
        return new ToolResult(
                true,
                content,
                metadata == null
                        ? Map.of()
                        : Map.copyOf(
                        metadata
                )
        );
    }

    public static ToolResult failure(
            String content
    ) {
        return new ToolResult(
                false,
                content,
                Map.of()
        );
    }
}