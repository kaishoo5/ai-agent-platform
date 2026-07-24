package com.agent.aiagent.domain.tool.model;

import java.util.Map;

public record ToolExecutionRequest(
        String toolName,
        Map<String, Object> arguments
) {
}