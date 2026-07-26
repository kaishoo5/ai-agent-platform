package com.agent.aiagent.infra.provider.ollama.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
public class OllamaChatMessage {

    private String role;
    private String content;
    private List<String> images;

    @JsonProperty("tool_calls")
    private List<OllamaToolCall> toolCalls;

    @JsonProperty("tool_name")
    private String toolName;

    public OllamaChatMessage(
            String role,
            String content,
            List<String> images
    ) {
        this(
                role,
                content,
                images,
                null,
                null
        );
    }

    public OllamaChatMessage(
            String role,
            String content,
            List<String> images,
            List<OllamaToolCall> toolCalls
    ) {
        this(
                role,
                content,
                images,
                toolCalls,
                null
        );
    }

    public OllamaChatMessage(
            String role,
            String content,
            List<String> images,
            List<OllamaToolCall> toolCalls,
            String toolName
    ) {
        this.role = role;
        this.content = content;
        this.images = images;
        this.toolCalls = toolCalls;
        this.toolName = toolName;
    }
}