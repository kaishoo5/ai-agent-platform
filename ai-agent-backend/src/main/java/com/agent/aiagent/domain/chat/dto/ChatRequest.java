package com.agent.aiagent.domain.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
public class ChatRequest {

    @NotBlank
    private String roomId;

    @NotEmpty
    private List<@Valid ChatMessageRequest> messages;

    private boolean regenerate;

    private List<String> fileIds = new ArrayList<>();
}