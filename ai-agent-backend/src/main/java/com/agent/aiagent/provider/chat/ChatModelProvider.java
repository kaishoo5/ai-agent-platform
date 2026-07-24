package com.agent.aiagent.provider.chat;

import com.agent.aiagent.infra.ollama.dto.OllamaChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatModelProvider {

    String chatOnce(
            ChatModelType modelType,
            List<ChatModelMessage> messages
    );

    Flux<OllamaChatResponse> chat(
            ChatModelType modelType,
            List<ChatModelMessage> messages
    );
}
