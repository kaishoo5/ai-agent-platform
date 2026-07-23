package com.agent.aiagent.infra.ollama.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OllamaChatRequest {

    private String model;

    private List<OllamaChatMessage> messages;

    private boolean stream;

}