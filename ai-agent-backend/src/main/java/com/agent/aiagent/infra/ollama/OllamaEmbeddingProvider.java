package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.provider.embedding.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OllamaEmbeddingProvider
        implements EmbeddingProvider {

    private final OllamaClient ollamaClient;

    @Override
    public List<List<Double>> embed(
            List<String> inputs
    ) {
        return ollamaClient.embed(
                inputs
        );
    }
}