package com.agent.aiagent.domain.chat.model;

import com.agent.aiagent.domain.rag.model.ChatSource;
import com.agent.aiagent.provider.chat.ChatModelMessage;

import java.util.List;

public record ChatMessageContext(
        List<ChatModelMessage> messages,
        List<ChatSource> sources
) {

    public ChatMessageContext {
        messages =
                messages == null
                        ? List.of()
                        : List.copyOf(
                        messages
                );

        sources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );
    }
}