package com.agent.aiagent.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessageEditRequest(
        @NotBlank
        String content
) {
}
