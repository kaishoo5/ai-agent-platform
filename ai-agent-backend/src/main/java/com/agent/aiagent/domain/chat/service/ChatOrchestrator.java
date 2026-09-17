package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatExecutionContext;
import com.agent.aiagent.domain.tool.service.ToolCallingExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private static final String USER_ROLE = "user";

    private final ConversationSummaryService conversationSummaryService;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatModelRequestFactory chatModelRequestFactory;
    private final ToolCallingExecutor toolCallingExecutor;

    public SseEmitter stream(ChatRequest request) {
        String roomId =
                request.getRoomId();

        String userContent =
                getLastUserMessageContent(
                        request
                );

        if (!request.isRegenerate()) {
            chatPersistenceService.saveUserMessage(
                    roomId,
                    userContent
            );
        }

        conversationSummaryService.refreshSummaryIfNeeded(
                roomId,
                request.isRegenerate()
        );

        ChatExecutionContext executionContext =
                chatModelRequestFactory.createContext(
                        request
                );

        log.info(
                "채팅 실행 컨텍스트 생성 완료. roomId={}, sourceCount={}",
                roomId,
                executionContext.sources().size()
        );

        return toolCallingExecutor.execute(
                request,
                executionContext.modelRequest(),
                executionContext.sources()
        );
    }

    private String getLastUserMessageContent(
            ChatRequest request
    ) {
        return request.getMessages()
                .stream()
                .filter(message ->
                        USER_ROLE.equalsIgnoreCase(
                                message.getRole()
                        )
                )
                .reduce((first, second) -> second)
                .map(message ->
                        message.getContent()
                )
                .filter(content ->
                        !content.isBlank()
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "사용자 메시지가 없습니다."
                        )
                );
    }
}