package com.agent.aiagent.domain.rag.model;

import java.util.List;

public record RagPromptResult(
        String prompt,
        List<ChatSource> sources
) {

    public RagPromptResult {
        sources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );
    }

    public static RagPromptResult withoutSources(
            String prompt
    ) {
        return new RagPromptResult(
                prompt,
                List.of()
        );
    }
}