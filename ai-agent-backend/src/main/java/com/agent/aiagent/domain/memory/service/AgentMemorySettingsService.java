package com.agent.aiagent.domain.memory.service;

import com.agent.aiagent.settings.entity.AppSetting;
import com.agent.aiagent.settings.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentMemorySettingsService {

    private static final String MEMORY_ENABLED_KEY =
            "MEMORY_ENABLED";

    private static final boolean DEFAULT_MEMORY_ENABLED =
            true;

    private final AppSettingRepository appSettingRepository;

    public boolean isEnabled() {
        return appSettingRepository
                .findById(
                        MEMORY_ENABLED_KEY
                )
                .map(AppSetting::getValue)
                .map(Boolean::parseBoolean)
                .orElse(
                        DEFAULT_MEMORY_ENABLED
                );
    }

    @Transactional
    public boolean updateEnabled(
            boolean enabled
    ) {
        AppSetting setting =
                appSettingRepository
                        .findById(
                                MEMORY_ENABLED_KEY
                        )
                        .orElseGet(() ->
                                new AppSetting(
                                        MEMORY_ENABLED_KEY,
                                        Boolean.toString(
                                                enabled
                                        )
                                )
                        );

        setting.updateValue(
                Boolean.toString(
                        enabled
                )
        );

        appSettingRepository.save(
                setting
        );

        return enabled;
    }
}
