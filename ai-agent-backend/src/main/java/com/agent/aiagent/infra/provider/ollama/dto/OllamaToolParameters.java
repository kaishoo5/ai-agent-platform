package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class OllamaToolParameters {

    private String type;

    private Map<String, Object> properties;

    private List<String> required;
}
