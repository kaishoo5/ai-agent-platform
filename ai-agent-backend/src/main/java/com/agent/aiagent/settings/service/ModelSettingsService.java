package com.agent.aiagent.settings.service;

import com.agent.aiagent.provider.chat.ModelProviderType;
import com.agent.aiagent.settings.dto.ModelSettingsUpdateRequest;
import com.agent.aiagent.settings.entity.AppSetting;
import com.agent.aiagent.settings.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ModelSettingsService {

    private static final String TEXT_MODEL_KEY = "TEXT_MODEL";
    private static final String VISION_MODEL_KEY = "VISION_MODEL";
    private static final String EMBEDDING_MODEL_KEY = "EMBEDDING_MODEL";

    private static final String DEFAULT_TEXT_MODEL = "gpt-oss:20b";
    private static final String DEFAULT_VISION_MODEL = "qwen3-vl:4b";
    private static final String DEFAULT_EMBEDDING_MODEL = "embeddinggemma:latest";

    private final AppSettingRepository appSettingRepository;
    private final String geminiModel;

    public ModelSettingsService(
            AppSettingRepository appSettingRepository,
            @Value("${gemini.api.model}") String geminiModel
    ) {
        this.appSettingRepository = appSettingRepository;
        this.geminiModel = geminiModel;
    }

    public String getTextModel() {
        return getValue(TEXT_MODEL_KEY, DEFAULT_TEXT_MODEL);
    }

    public String getVisionModel() {
        return getValue(VISION_MODEL_KEY, DEFAULT_VISION_MODEL);
    }

    public String getEmbeddingModel() {
        return getValue(EMBEDDING_MODEL_KEY, DEFAULT_EMBEDDING_MODEL);
    }

    @Transactional
    public void updateModels(
            ModelSettingsUpdateRequest request,
            List<String> installedModels
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "모델 설정이 필요합니다."
            );
        }

        String textModel = normalizeModel(request.textModel());
        String visionModel = normalizeModel(request.visionModel());
        String embeddingModel = normalizeModel(request.embeddingModel());

        validateModel(textModel, installedModels, true);
        validateModel(visionModel, installedModels, true);
        validateModel(embeddingModel, installedModels, false);

        saveValue(TEXT_MODEL_KEY, textModel);
        saveValue(VISION_MODEL_KEY, visionModel);
        saveValue(EMBEDDING_MODEL_KEY, embeddingModel);
    }

    private void validateModel(
            String modelId,
            List<String> installedModels,
            boolean allowGemini
    ) {
        ModelProviderType provider =
                ModelProviderType.fromModelId(modelId);

        String modelName =
                ModelProviderType.resolveModelName(modelId);

        if (modelName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "모델명이 비어 있습니다."
            );
        }

        if (provider == ModelProviderType.GEMINI) {
            if (!allowGemini) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Gemini 임베딩 모델은 아직 지원하지 않습니다."
                );
            }

            if (!geminiModel.equals(modelName)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "지원하지 않는 Gemini 모델입니다: " + modelName
                );
            }

            return;
        }

        validateInstalledOllama(modelName, installedModels);
    }

    private void validateInstalledOllama(
            String modelName,
            List<String> installedModels
    ) {
        if (installedModels == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Ollama에 연결할 수 없습니다."
            );
        }

        boolean installed = installedModels.stream()
                .anyMatch(installedModel ->
                        installedModel.equals(modelName)
                                || installedModel.startsWith(modelName + ":")
                );

        if (!installed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "설치되지 않은 Ollama 모델입니다: " + modelName
            );
        }
    }

    private String getValue(
            String key,
            String defaultValue
    ) {
        return appSettingRepository.findById(key)
                .map(AppSetting::getValue)
                .filter(value ->
                        value != null && !value.isBlank()
                )
                .orElse(defaultValue);
    }

    private void saveValue(
            String key,
            String value
    ) {
        AppSetting setting = appSettingRepository
                .findById(key)
                .orElseGet(() -> new AppSetting(key, value));

        setting.updateValue(value);
        appSettingRepository.save(setting);
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "모델명을 입력해주세요."
            );
        }

        return model.trim();
    }
}
