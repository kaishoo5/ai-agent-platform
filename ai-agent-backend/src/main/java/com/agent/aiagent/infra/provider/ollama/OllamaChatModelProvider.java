package com.agent.aiagent.infra.provider.ollama;

import com.agent.aiagent.infra.ollama.OllamaClient;
import com.agent.aiagent.infra.provider.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.infra.provider.ollama.dto.OllamaChatResponse;
import com.agent.aiagent.infra.provider.ollama.dto.OllamaTool;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OllamaChatModelProvider
        implements ChatModelProvider {

    private final OllamaClient ollamaClient;
    private final OllamaToolMapper ollamaToolMapper;

    @Override
    public ChatModelResponse chatOnce(
            ChatModelRequest request
    ) {
        OllamaChatResponse response =
                ollamaClient.chatOnce(
                        resolveModel(request.modelType()),
                        toOllamaMessages(request.messages()),
                        request.tools()
                                .stream()
                                .map(ollamaToolMapper::map)
                                .toList()
                );

        return new ChatModelResponse(
                response.getMessage() == null
                        ? ""
                        : response.getMessage().getContent(),
                response.isDone(),
                toChatModelToolCalls(response)
        );
    }

    @Override
    public Flux<ChatModelResponse> chat(
            ChatModelRequest request
    ) {
        return ollamaClient
                .chat(
                        resolveModel(
                                request.modelType()
                        ),
                        toOllamaMessages(
                                request.messages()
                        ),
                        toOllamaTools(
                                request.tools()
                        )
                )
                .map(
                        this::toChatModelResponse
                );
    }

    private List<OllamaChatMessage> toOllamaMessages(
            List<ChatModelMessage> messages
    ) {
        return messages.stream()
                .map(message ->
                        new OllamaChatMessage(
                                message.getRole(),
                                message.getContent(),
                                message.getImages()
                        )
                )
                .toList();
    }

    private List<OllamaTool> toOllamaTools(
            List<ChatModelTool> tools
    ) {
        return tools.stream()
                .map(
                        ollamaToolMapper::map
                )
                .toList();
    }

    private ChatModelResponse toChatModelResponse(
            OllamaChatResponse response
    ) {
        String content = null;

        if (response.getMessage() != null) {
            content = response
                    .getMessage()
                    .getContent();
        }

        return new ChatModelResponse(
                content,
                response.isDone(),
                toChatModelToolCalls(response)
        );
    }

    private List<ChatModelToolCall> toChatModelToolCalls(
            OllamaChatResponse response
    ) {
        if (
                response.getMessage() == null
                        || response.getMessage().getToolCalls() == null
        ) {
            return List.of();
        }

        return response.getMessage()
                .getToolCalls()
                .stream()
                .filter(toolCall ->
                        toolCall != null
                                && toolCall.getFunction() != null
                )
                .map(toolCall ->
                        new ChatModelToolCall(
                                toolCall.getFunction().getName(),
                                toolCall.getFunction().getArguments()
                        )
                )
                .toList();
    }

    private String resolveModel(
            ChatModelType modelType
    ) {
        return switch (modelType) {
            case TEXT -> OllamaClient.MODEL_TEXT;
            case VISION -> OllamaClient.MODEL_VISION;
        };
    }
}