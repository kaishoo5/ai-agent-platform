package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolResult;

import java.util.Map;

public interface AgentTool {

    String getName();

    String getDescription();

    ToolResult execute(
            Map<String, Object> arguments
    );
}