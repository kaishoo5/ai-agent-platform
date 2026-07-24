package com.agent.aiagent.provider.chat;

import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatModelProvider {

    String chatOnce(
            ChatModelType modelType,
            List<ChatModelMessage> messages
    );

    Flux<ChatModelResponse> chat(
            ChatModelType modelType,
            List<ChatModelMessage> messages
    );
}
