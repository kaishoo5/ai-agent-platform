package com.agent.aiagent.infra.ollama.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class OllamaEmbeddingRequest {

    private String model;

    private List<String> input;
}