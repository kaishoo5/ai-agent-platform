package com.agent.aiagent.domain.chat.model;

public record AgentStep(
        String code,
        String status,
        String message
) {

    public static AgentStep running(
            String code,
            String message
    ) {
        return new AgentStep(
                code,
                "running",
                message
        );
    }

    public static AgentStep completed(
            String code,
            String message
    ) {
        return new AgentStep(
                code,
                "completed",
                message
        );
    }
}