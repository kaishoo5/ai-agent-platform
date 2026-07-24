package com.agent.aiagent.infra.ollama.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
public class OllamaEmbeddingResponse {

    private String model;

    private List<List<Double>> embeddings =
            new ArrayList<>();
}