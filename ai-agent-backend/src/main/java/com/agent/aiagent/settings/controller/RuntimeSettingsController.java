package com.agent.aiagent.settings.controller;

import com.agent.aiagent.settings.dto.ModelSettingsUpdateRequest;
import com.agent.aiagent.settings.dto.RuntimeSettingsResponse;
import com.agent.aiagent.settings.service.RuntimeSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settings")
public class RuntimeSettingsController {

    private final RuntimeSettingsService runtimeSettingsService;

    @GetMapping("/runtime")
    public RuntimeSettingsResponse getRuntimeSettings() {
        return runtimeSettingsService.getRuntimeSettings();
    }

    @PutMapping("/models")
    public RuntimeSettingsResponse updateModels(
            @RequestBody ModelSettingsUpdateRequest request
    ) {
        return runtimeSettingsService.updateModels(
                request
        );
    }
}
