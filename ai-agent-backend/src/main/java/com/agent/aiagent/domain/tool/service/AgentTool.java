package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;

import java.util.Map;

public interface AgentTool {

    ToolSpecification getSpecification();

    ToolResult execute(Map<String, Object> arguments);

    default String getName() {
        return getSpecification().name();
    }

    default String getDescription() {
        return getSpecification().description();
    }
}