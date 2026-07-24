package com.agent.aiagent.infra.provider.ollama.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OllamaChatMessage {

    private String role;
    private String content;
    private List<String> images;

    @JsonProperty("tool_calls")
    private List<OllamaToolCall> toolCalls;

    public OllamaChatMessage(
            String role,
            String content,
            List<String> images
    ) {
        this.role = role;
        this.content = content;
        this.images = images;
        this.toolCalls = List.of();
    }
}