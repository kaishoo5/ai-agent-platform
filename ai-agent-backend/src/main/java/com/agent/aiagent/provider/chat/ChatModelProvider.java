package com.agent.aiagent.provider.chat;

import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.infra.ollama.dto.OllamaChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatModelProvider {

    String chatOnce(
            String model,
            List<OllamaChatMessage> messages
    );

    Flux<OllamaChatResponse> chat(
            String model,
            List<OllamaChatMessage> messages
    );
}
