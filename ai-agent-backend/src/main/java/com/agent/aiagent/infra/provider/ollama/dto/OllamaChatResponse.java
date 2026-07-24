package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OllamaChatResponse {

    private String model;

    private OllamaChatMessage message;

    private boolean done;

}