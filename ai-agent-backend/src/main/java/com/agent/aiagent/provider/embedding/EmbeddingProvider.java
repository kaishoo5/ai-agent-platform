package com.agent.aiagent.provider.embedding;

import java.util.List;

public interface EmbeddingProvider {

    List<List<Double>> embed(
            List<String> inputs
    );
}