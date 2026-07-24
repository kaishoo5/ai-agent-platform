package com.agent.aiagent.provider.chat;

import reactor.core.publisher.Flux;

public interface ChatModelProvider {

    ChatModelResponse chatOnce(
            ChatModelRequest request
    );

    Flux<ChatModelResponse> chat(
            ChatModelRequest request
    );
}