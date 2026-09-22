package com.agent.aiagent.domain.chat.model;

import com.agent.aiagent.domain.rag.model.ChatSource;
import com.agent.aiagent.provider.chat.ChatModelRequest;

import java.util.List;

public record ChatExecutionContext(
        ChatModelRequest modelRequest,
        List<ChatSource> sources,
        List<String> documentFileIds
) {

    public ChatExecutionContext {
        sources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );

        documentFileIds =
                documentFileIds == null
                        ? List.of()
                        : List.copyOf(
                        documentFileIds
                );
    }
}