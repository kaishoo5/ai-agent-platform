package com.agent.aiagent.infra.provider.router;

import com.agent.aiagent.infra.provider.gemini.GeminiChatModelProvider;
import com.agent.aiagent.infra.provider.ollama.OllamaChatModelProvider;
import com.agent.aiagent.provider.chat.*;
import com.agent.aiagent.settings.service.ModelSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Primary
@Component
public class RoutingChatModelProvider implements ChatModelProvider {

    private final OllamaChatModelProvider ollamaProvider;
    private final GeminiChatModelProvider geminiProvider;
    private final ModelSettingsService modelSettingsService;

    public RoutingChatModelProvider(
            OllamaChatModelProvider ollamaProvider,
            GeminiChatModelProvider geminiProvider,
            ModelSettingsService modelSettingsService
    ) {
        this.ollamaProvider = ollamaProvider;
        this.geminiProvider = geminiProvider;
        this.modelSettingsService = modelSettingsService;
    }

    @Override
    public ChatModelResponse chatOnce(ChatModelRequest request) {
        ChatModelProvider provider = resolveProvider(request);

        return provider.chatOnce(request);
    }

    @Override
    public Flux<ChatModelResponse> chat(ChatModelRequest request) {
        return Flux.defer(() -> {
            ChatModelProvider provider = resolveProvider(request);

            return provider.chat(request);
        });
    }

    private ChatModelProvider resolveProvider(ChatModelRequest request) {
        if (request == null || request.modelType() == null) {
            throw new IllegalArgumentException(
                    "채팅 요청 또는 모델 유형이 비어 있습니다."
            );
        }

        String modelId = resolveModelId(request.modelType());

        ModelProviderType providerType =
                ModelProviderType.fromModelId(modelId);

        log.info(
                "ChatModelProvider 라우팅. modelType={}, provider={}, model={}",
                request.modelType(),
                providerType,
                modelId
        );

        return switch (providerType) {
            case OLLAMA -> ollamaProvider;
            case GEMINI -> geminiProvider;
        };
    }

    private String resolveModelId(ChatModelType modelType) {
        return switch (modelType) {
            case TEXT -> modelSettingsService.getTextModel();
            case VISION -> modelSettingsService.getVisionModel();
        };
    }
}
