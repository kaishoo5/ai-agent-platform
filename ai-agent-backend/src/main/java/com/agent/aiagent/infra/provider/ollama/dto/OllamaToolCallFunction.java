package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OllamaToolCallFunction {

    private String name;
    private Map<String, Object> arguments;
}