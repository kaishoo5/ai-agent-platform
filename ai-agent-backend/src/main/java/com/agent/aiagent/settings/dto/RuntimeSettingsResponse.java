package com.agent.aiagent.settings.dto;

import java.util.List;

public record RuntimeSettingsResponse(
        String backendStatus,
        String ollamaStatus,
        String ollamaEndpoint,
        String textModel,
        String visionModel,
        String embeddingModel,
        List<String> installedModels
) {
}
