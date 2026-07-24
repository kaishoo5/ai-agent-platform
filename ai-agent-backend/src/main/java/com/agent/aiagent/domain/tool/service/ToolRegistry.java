package com.agent.aiagent.domain.tool.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(
            List<AgentTool> agentTools
    ) {
        Map<String, AgentTool> registeredTools =
                new LinkedHashMap<>();

        for (AgentTool agentTool : agentTools) {
            String toolName =
                    agentTool.getName();

            if (registeredTools.containsKey(toolName)) {
                throw new IllegalStateException(
                        "중복된 Tool 이름입니다: "
                                + toolName
                );
            }

            registeredTools.put(
                    toolName,
                    agentTool
            );
        }

        this.tools =
                Map.copyOf(
                        registeredTools
                );

        log.info(
                "Agent Tool 등록 완료. toolCount={}, tools={}",
                tools.size(),
                tools.keySet()
        );
    }

    public List<AgentTool> getTools() {
        return List.copyOf(
                tools.values()
        );
    }

    public Optional<AgentTool> find(
            String toolName
    ) {
        if (
                toolName == null
                        || toolName.isBlank()
        ) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                tools.get(toolName)
        );
    }
}