package com.agent.aiagent.provider.chat;

import java.util.List;

public record ChatModelRequest(
        ChatModelType modelType,
        List<ChatModelMessage> messages,
        List<ChatModelTool> tools
) {

    public ChatModelRequest {
        messages =
                messages == null
                        ? List.of()
                        : List.copyOf(
                        messages
                );

        tools =
                tools == null
                        ? List.of()
                        : List.copyOf(
                        tools
                );
    }
}