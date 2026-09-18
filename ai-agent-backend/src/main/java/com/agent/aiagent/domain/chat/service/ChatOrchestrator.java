package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatExecutionContext;
import com.agent.aiagent.domain.tool.service.ToolCallingExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatOrchestrator {

    private static final String USER_ROLE = "user";

    private final ConversationSummaryService conversationSummaryService;
    private final ChatPersistenceService chatPersistenceService;
    private final ChatModelRequestFactory chatModelRequestFactory;
    private final ToolCallingExecutor toolCallingExecutor;
    private final ChatStreamingExecutor chatStreamingExecutor;
    private final ObjectMapper objectMapper;
    private final Executor chatExecutor;

    public ChatOrchestrator(
            ConversationSummaryService conversationSummaryService,
            ChatPersistenceService chatPersistenceService,
            ChatModelRequestFactory chatModelRequestFactory,
            ToolCallingExecutor toolCallingExecutor,
            ChatStreamingExecutor chatStreamingExecutor,
            ObjectMapper objectMapper,
            @Qualifier("chatExecutor") Executor chatExecutor
    ) {
        this.conversationSummaryService =
                conversationSummaryService;

        this.chatPersistenceService =
                chatPersistenceService;

        this.chatModelRequestFactory =
                chatModelRequestFactory;

        this.toolCallingExecutor =
                toolCallingExecutor;

        this.chatStreamingExecutor =
                chatStreamingExecutor;

        this.objectMapper =
                objectMapper;

        this.chatExecutor =
                chatExecutor;
    }

    public SseEmitter stream(
            ChatRequest request
    ) {
        SseEmitter emitter =
                chatStreamingExecutor.createEmitter();

        chatExecutor.execute(() ->
                execute(
                        emitter,
                        request
                )
        );

        return emitter;
    }

    private void execute(
            SseEmitter emitter,
            ChatRequest request
    ) {
        String roomId =
                request.getRoomId();

        AtomicBoolean progressTerminated =
                new AtomicBoolean(false);

        AgentProgressReporter progressReporter =
                new AgentProgressReporter(
                        emitter,
                        objectMapper,
                        progressTerminated
                );

        try {
            progressReporter.running(
                    "request_prepare",
                    "요청 준비 중..."
            );

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
                            request,
                            progressReporter
                    );

            progressReporter.completed(
                    "request_prepare",
                    "요청 준비 완료"
            );

            log.info(
                    "채팅 실행 컨텍스트 생성 완료. roomId={}, sourceCount={}",
                    roomId,
                    executionContext.sources().size()
            );

            toolCallingExecutor.execute(
                    emitter,
                    request,
                    executionContext.modelRequest(),
                    executionContext.sources(),
                    progressReporter
            );
        } catch (Exception exception) {
            log.error(
                    "채팅 실행 중 오류가 발생했습니다. roomId={}",
                    roomId,
                    exception
            );

            try {
                emitter.completeWithError(
                        exception
                );
            } catch (Exception completeException) {
                log.debug(
                        "SSE 오류 종료 처리 중 예외가 발생했습니다. roomId={}",
                        roomId,
                        completeException
                );
            }
        }
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