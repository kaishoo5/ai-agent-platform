package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OllamaToolCall {

    private OllamaToolCallFunction function;
}