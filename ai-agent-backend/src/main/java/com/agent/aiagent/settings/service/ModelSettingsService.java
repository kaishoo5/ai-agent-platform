package com.agent.aiagent.settings.service;

import com.agent.aiagent.settings.dto.ModelSettingsUpdateRequest;
import com.agent.aiagent.settings.entity.AppSetting;
import com.agent.aiagent.settings.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModelSettingsService {

    private static final String TEXT_MODEL_KEY =
            "TEXT_MODEL";

    private static final String VISION_MODEL_KEY =
            "VISION_MODEL";

    private static final String EMBEDDING_MODEL_KEY =
            "EMBEDDING_MODEL";

    private static final String DEFAULT_TEXT_MODEL =
            "gpt-oss:20b";

    private static final String DEFAULT_VISION_MODEL =
            "qwen3-vl:4b";

    private static final String DEFAULT_EMBEDDING_MODEL =
            "embeddinggemma:latest";

    private final AppSettingRepository appSettingRepository;

    public String getTextModel() {
        return getValue(
                TEXT_MODEL_KEY,
                DEFAULT_TEXT_MODEL
        );
    }

    public String getVisionModel() {
        return getValue(
                VISION_MODEL_KEY,
                DEFAULT_VISION_MODEL
        );
    }

    public String getEmbeddingModel() {
        return getValue(
                EMBEDDING_MODEL_KEY,
                DEFAULT_EMBEDDING_MODEL
        );
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

        String textModel =
                normalizeModel(
                        request.textModel()
                );

        String visionModel =
                normalizeModel(
                        request.visionModel()
                );

        String embeddingModel =
                normalizeModel(
                        request.embeddingModel()
                );

        validateInstalled(
                textModel,
                installedModels
        );

        validateInstalled(
                visionModel,
                installedModels
        );

        validateInstalled(
                embeddingModel,
                installedModels
        );

        saveValue(
                TEXT_MODEL_KEY,
                textModel
        );

        saveValue(
                VISION_MODEL_KEY,
                visionModel
        );

        saveValue(
                EMBEDDING_MODEL_KEY,
                embeddingModel
        );
    }

    private String getValue(
            String key,
            String defaultValue
    ) {
        return appSettingRepository
                .findById(key)
                .map(AppSetting::getValue)
                .filter(value ->
                        value != null
                                && !value.isBlank()
                )
                .orElse(defaultValue);
    }

    private void saveValue(
            String key,
            String value
    ) {
        AppSetting setting =
                appSettingRepository
                        .findById(key)
                        .orElseGet(() ->
                                new AppSetting(
                                        key,
                                        value
                                )
                        );

        setting.updateValue(
                value
        );

        appSettingRepository.save(
                setting
        );
    }

    private String normalizeModel(
            String model
    ) {
        if (
                model == null
                        || model.isBlank()
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "모델명을 입력해주세요."
            );
        }

        return model.trim();
    }

    private void validateInstalled(
            String model,
            List<String> installedModels
    ) {
        boolean installed =
                installedModels
                        .stream()
                        .anyMatch(installedModel ->
                                installedModel.equals(model)
                                        || installedModel.startsWith(
                                        model + ":"
                                )
                        );

        if (!installed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "설치되지 않은 Ollama 모델입니다: "
                            + model
            );
        }
    }
}
