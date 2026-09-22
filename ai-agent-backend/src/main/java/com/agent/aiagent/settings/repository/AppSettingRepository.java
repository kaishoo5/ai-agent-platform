package com.agent.aiagent.settings.repository;

import com.agent.aiagent.settings.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository
        extends JpaRepository<AppSetting, String> {
}
