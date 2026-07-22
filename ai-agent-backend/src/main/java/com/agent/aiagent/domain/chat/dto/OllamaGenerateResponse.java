package com.agent.aiagent.domain.chat.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OllamaGenerateResponse {

    private String model;

    private String response;

    private boolean done;

}