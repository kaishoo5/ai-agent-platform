package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.infra.ollama.dto.OllamaChatOptions;
import com.agent.aiagent.infra.ollama.dto.OllamaChatRequest;
import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class OllamaRequestBuilder {

    private static final int TEXT_CONTEXT_SIZE = 8_192;
    private static final int VISION_CONTEXT_SIZE = 16_384;

    public OllamaChatRequest build(
            String model,
            List<OllamaChatMessage> messages,
            boolean stream
    ) {
        int contextSize =
                OllamaClient.MODEL_VISION.equals(model)
                        ? VISION_CONTEXT_SIZE
                        : TEXT_CONTEXT_SIZE;

        return OllamaChatRequest.builder()
                .model(model)
                .messages(messages)
                .options(
                        new OllamaChatOptions(
                                contextSize
                        )
                )
                .stream(stream)
                .build();
    }

    public OllamaChatRequest build(
            String model,
            List<OllamaChatMessage> messages
    ) {
        return build(
                model,
                messages,
                true
        );
    }
}