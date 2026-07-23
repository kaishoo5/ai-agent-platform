package com.agent.aiagent.infra.ollama.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OllamaChatResponse {

    private String model;

    private OllamaChatMessage message;

    private boolean done;

}