package com.agent.aiagent.domain.memory.controller;

import com.agent.aiagent.domain.memory.dto.AgentMemoryEnabledResponse;
import com.agent.aiagent.domain.memory.dto.AgentMemoryEnabledUpdateRequest;
import com.agent.aiagent.domain.memory.dto.AgentMemoryResponse;
import com.agent.aiagent.domain.memory.dto.AgentMemoryUpdateRequest;
import com.agent.aiagent.domain.memory.service.AgentMemoryService;
import com.agent.aiagent.domain.memory.service.AgentMemorySettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settings/memory")
public class AgentMemoryController {

    private final AgentMemoryService agentMemoryService;
    private final AgentMemorySettingsService agentMemorySettingsService;

    @GetMapping
    public List<AgentMemoryResponse> getMemories() {
        return agentMemoryService
                .findAllMemories()
                .stream()
                .map(
                        AgentMemoryResponse::from
                )
                .toList();
    }

    @GetMapping("/enabled")
    public AgentMemoryEnabledResponse getEnabled() {
        return new AgentMemoryEnabledResponse(
                agentMemorySettingsService.isEnabled()
        );
    }

    @PutMapping("/enabled")
    public AgentMemoryEnabledResponse updateEnabled(
            @RequestBody AgentMemoryEnabledUpdateRequest request
    ) {
        return new AgentMemoryEnabledResponse(
                agentMemorySettingsService.updateEnabled(
                        request.enabled()
                )
        );
    }

    @PutMapping("/{memoryId}")
    public AgentMemoryResponse updateMemory(
            @PathVariable String memoryId,
            @Valid
            @RequestBody AgentMemoryUpdateRequest request
    ) {
        return AgentMemoryResponse.from(
                agentMemoryService.updateMemory(
                        memoryId,
                        request.category(),
                        request.content()
                )
        );
    }

    @DeleteMapping("/{memoryId}")
    public void deleteMemory(
            @PathVariable String memoryId
    ) {
        agentMemoryService.deleteMemory(
                memoryId
        );
    }

    @DeleteMapping
    public void deleteAllMemories() {
        agentMemoryService.deleteAllMemories();
    }
}
