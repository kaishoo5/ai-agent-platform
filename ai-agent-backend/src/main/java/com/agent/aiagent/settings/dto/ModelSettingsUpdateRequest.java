package com.agent.aiagent.settings.dto;

public record ModelSettingsUpdateRequest(
        String textModel,
        String visionModel,
        String embeddingModel
) {
}
