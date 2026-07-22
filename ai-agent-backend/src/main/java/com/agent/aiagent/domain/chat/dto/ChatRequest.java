package com.agent.aiagent.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ChatRequest {

    @NotBlank(message = "메시지는 필수입니다.")
    private String message;

}