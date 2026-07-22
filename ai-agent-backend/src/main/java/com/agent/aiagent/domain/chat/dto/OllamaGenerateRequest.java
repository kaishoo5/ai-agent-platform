package com.agent.aiagent.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OllamaGenerateRequest {

    private String model;

    private String prompt;

    private boolean stream;

}