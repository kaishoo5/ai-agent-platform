package com.agent.aiagent.domain.rag.model;

import java.util.List;

public record FilePromptResult(
        String prompt,
        List<ChatSource> sources
) {

    public FilePromptResult {
        sources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );
    }
}