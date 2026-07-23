package com.agent.aiagent.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatMessageRequest {

    @NotBlank
    private String role;

    @NotBlank
    private String content;

}