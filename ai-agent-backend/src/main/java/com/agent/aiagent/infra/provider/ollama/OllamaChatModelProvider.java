package com.agent.aiagent.infra.provider.ollama;

import com.agent.aiagent.infra.ollama.OllamaClient;
import com.agent.aiagent.infra.provider.ollama.dto.*;
import com.agent.aiagent.provider.chat.*;
import com.agent.aiagent.settings.service.ModelSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class OllamaChatModelProvider
        implements ChatModelProvider {

    private final OllamaClient ollamaClient;
    private final OllamaToolMapper ollamaToolMapper;
    private final ObjectMapper objectMapper;
    private final ModelSettingsService modelSettingsService;

    @Override
    public ChatModelResponse chatOnce(
            ChatModelRequest request
    ) {
        OllamaChatResponse response =
                ollamaClient.chatOnce(
                        resolveModel(
                                request.modelType()
                        ),
                        toOllamaMessages(
                                request.messages()
                        ),
                        request.tools()
                                .stream()
                                .map(
                                        ollamaToolMapper::map
                                )
                                .toList()
                );

        String content =
                response.getMessage() == null
                        ? ""
                        : response.getMessage()
                        .getContent();

        List<ChatModelToolCall> toolCalls =
                toChatModelToolCalls(
                        response
                );

        if (
                toolCalls.isEmpty()
                        && !request.tools().isEmpty()
        ) {
            toolCalls =
                    parseContentToolCall(
                            content,
                            request.tools()
                    );

            if (!toolCalls.isEmpty()) {
                content = "";
            }
        }

        return new ChatModelResponse(
                content,
                response.isDone(),
                toolCalls
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
                                message.getImages(),
                                message.getToolCalls()
                                        .stream()
                                        .map(toolCall ->
                                                new OllamaToolCall(
                                                        new OllamaToolCallFunction(
                                                                toolCall.name(),
                                                                toolCall.arguments()
                                                        )
                                                )
                                        )
                                        .toList(),
                                message.getToolName()
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
                toChatModelToolCalls(
                        response
                )
        );
    }

    private List<ChatModelToolCall> toChatModelToolCalls(
            OllamaChatResponse response
    ) {
        if (
                response.getMessage() == null
                        || response.getMessage()
                        .getToolCalls() == null
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
                                toolCall.getFunction()
                                        .getName(),
                                toolCall.getFunction()
                                        .getArguments()
                        )
                )
                .toList();
    }

    private List<ChatModelToolCall> parseContentToolCall(
            String content,
            List<ChatModelTool> availableTools
    ) {
        if (
                content == null
                        || content.isBlank()
                        || availableTools == null
                        || availableTools.isEmpty()
        ) {
            return List.of();
        }

        String json =
                normalizeJsonResponse(
                        content
                );

        if (
                !json.startsWith("{")
                        || !json.endsWith("}")
        ) {
            return List.of();
        }

        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(
                            json,
                            new TypeReference<Map<String, Object>>() {
                            }
                    );

            Object nameValue =
                    parsed.get(
                            "name"
                    );

            Object argumentsValue =
                    parsed.get(
                            "arguments"
                    );

            if (
                    nameValue == null
                            || argumentsValue == null
            ) {
                return List.of();
            }

            String toolName =
                    nameValue.toString()
                            .trim();

            if (toolName.isBlank()) {
                return List.of();
            }

            Set<String> availableToolNames =
                    availableTools.stream()
                            .map(
                                    ChatModelTool::name
                            )
                            .collect(
                                    Collectors.toSet()
                            );

            if (
                    !availableToolNames.contains(
                            toolName
                    )
            ) {
                return List.of();
            }

            if (
                    !(argumentsValue
                            instanceof Map<?, ?> rawArguments)
            ) {
                return List.of();
            }

            Map<String, Object> arguments =
                    rawArguments.entrySet()
                            .stream()
                            .filter(entry ->
                                    entry.getKey() != null
                            )
                            .collect(
                                    Collectors.toMap(
                                            entry ->
                                                    entry.getKey()
                                                            .toString(),
                                            Map.Entry::getValue
                                    )
                            );

            return List.of(
                    new ChatModelToolCall(
                            toolName,
                            arguments
                    )
            );
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private String normalizeJsonResponse(
            String content
    ) {
        String normalized =
                content.trim();

        if (
                normalized.startsWith(
                        "```json"
                )
        ) {
            normalized =
                    normalized.substring(
                            "```json".length()
                    );
        } else if (
                normalized.startsWith(
                        "```"
                )
        ) {
            normalized =
                    normalized.substring(
                            "```".length()
                    );
        }

        if (
                normalized.endsWith(
                        "```"
                )
        ) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length()
                                    - "```".length()
                    );
        }

        return normalized.trim();
    }

    private String resolveModel(
            ChatModelType modelType
    ) {
        return switch (modelType) {
            case TEXT ->
                    modelSettingsService.getTextModel();

            case VISION ->
                    modelSettingsService.getVisionModel();
        };
    }
}