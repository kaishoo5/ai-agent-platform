package com.agent.aiagent.infra.provider.ollama.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OllamaChatOptions {

    private int num_ctx;
}