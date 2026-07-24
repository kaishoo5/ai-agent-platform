package com.agent.aiagent.domain.tool.model;

import java.util.Map;

public record ToolSpecification(
        String name,
        String description,
        Map<String, ToolParameter> parameters
) {
}