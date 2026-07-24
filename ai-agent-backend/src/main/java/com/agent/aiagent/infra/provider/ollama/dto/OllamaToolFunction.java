package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OllamaToolFunction {

    private String name;

    private String description;

    private OllamaToolParameters parameters;
}
