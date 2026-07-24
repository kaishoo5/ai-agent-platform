package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OllamaChatRequest {

    private String model;

    private List<OllamaChatMessage> messages;

    private List<OllamaTool> tools;

    private OllamaChatOptions options;

    private boolean stream;
}