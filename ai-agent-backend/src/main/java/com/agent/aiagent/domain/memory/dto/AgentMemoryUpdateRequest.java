package com.agent.aiagent.domain.memory.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentMemoryUpdateRequest(
        @NotBlank
        String category,

        @NotBlank
        String content
) {
}
