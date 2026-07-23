package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ChatService {

    public ChatResponse chat(ChatRequest request) {

//        String responseMessage = String.format(
//                "\"%s\" 메시지를 Spring Boot에서 정상적으로 받았습니다.",
//                request.getMessage()
//        );

        log.info("ChatService 호출!!");

        return new ChatResponse(null);

    }

}