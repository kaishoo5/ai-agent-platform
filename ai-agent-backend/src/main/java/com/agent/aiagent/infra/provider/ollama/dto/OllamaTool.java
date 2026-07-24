package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OllamaTool {

    private String type;

    private OllamaToolFunction function;
}
