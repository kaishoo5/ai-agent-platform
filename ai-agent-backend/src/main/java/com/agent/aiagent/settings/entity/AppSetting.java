package com.agent.aiagent.settings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "app_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppSetting {

    @Id
    @Column(name = "SETTING_KEY", length = 100, nullable = false)
    private String key;

    @Column(name = "SETTING_VALUE", length = 500, nullable = false)
    private String value;

    public AppSetting(
            String key,
            String value
    ) {
        this.key = key;
        this.value = value;
    }

    public void updateValue(
            String value
    ) {
        this.value = value;
    }
}
