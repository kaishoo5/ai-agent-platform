package com.agent.aiagent.settings.service;

import com.agent.aiagent.infra.ollama.OllamaConfig;
import com.agent.aiagent.settings.dto.ModelSettingsUpdateRequest;
import com.agent.aiagent.settings.dto.RuntimeSettingsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RuntimeSettingsService {

    private final WebClient ollamaWebClient;
    private final ModelSettingsService modelSettingsService;
    private final String geminiModel;

    public RuntimeSettingsService(
            WebClient ollamaWebClient,
            ModelSettingsService modelSettingsService,
            @Value("${gemini.api.model}") String geminiModel
    ) {
        this.ollamaWebClient = ollamaWebClient;
        this.modelSettingsService = modelSettingsService;
        this.geminiModel = geminiModel;
    }

    public RuntimeSettingsResponse getRuntimeSettings() {
        List<String> installedModels = getInstalledModels();

        return createResponse(installedModels);
    }

    public RuntimeSettingsResponse updateModels(
            ModelSettingsUpdateRequest request
    ) {
        List<String> installedModels = getInstalledModels();

        modelSettingsService.updateModels(
                request,
                installedModels
        );

        return createResponse(installedModels);
    }

    private RuntimeSettingsResponse createResponse(
            List<String> installedModels
    ) {
        String ollamaStatus =
                installedModels == null ? "DOWN" : "UP";

        List<String> geminiModels = List.of(
                "gemini:" + geminiModel
        );

        return new RuntimeSettingsResponse(
                "UP",
                ollamaStatus,
                OllamaConfig.OLLAMA_BASE_URL,
                modelSettingsService.getTextModel(),
                modelSettingsService.getVisionModel(),
                modelSettingsService.getEmbeddingModel(),
                installedModels == null
                        ? List.of()
                        : installedModels,
                geminiModels
        );
    }

    private List<String> getInstalledModels() {
        try {
            JsonNode response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(3));

            if (response == null) {
                return List.of();
            }

            JsonNode models = response.path("models");

            if (!models.isArray()) {
                return List.of();
            }

            List<String> installedModels = new ArrayList<>();

            models.forEach(model -> {
                String name = model.path("name").asText("");

                if (!name.isBlank()) {
                    installedModels.add(name);
                }
            });

            return installedModels;

        } catch (Exception e) {
            log.warn(
                    "Ollama 상태 확인에 실패했습니다. endpoint={}, message={}",
                    OllamaConfig.OLLAMA_BASE_URL,
                    e.getMessage()
            );

            return null;
        }
    }
}
