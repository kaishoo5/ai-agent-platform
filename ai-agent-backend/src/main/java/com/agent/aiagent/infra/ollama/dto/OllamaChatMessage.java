package com.agent.aiagent.infra.ollama.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OllamaChatMessage {

    private String role;

    private String content;

}