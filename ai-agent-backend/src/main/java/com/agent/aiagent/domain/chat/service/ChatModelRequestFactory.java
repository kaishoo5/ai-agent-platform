package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatAttachmentContext;
import com.agent.aiagent.domain.chat.model.ChatExecutionContext;
import com.agent.aiagent.domain.chat.model.ChatMessageContext;
import com.agent.aiagent.domain.tool.service.ToolRegistry;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelRequestFactory {

    private final ChatAttachmentContextFactory chatAttachmentContextFactory;
    private final ChatMessageContextFactory chatMessageContextFactory;
    private final ToolRegistry toolRegistry;
    private final ChatModelToolMapper chatModelToolMapper;

    public ChatModelRequest create(
            ChatRequest request
    ) {
        return createContext(
                request
        ).modelRequest();
    }

    public ChatExecutionContext createContext(
            ChatRequest request
    ) {
        return createContext(
                request,
                null
        );
    }

    public ChatExecutionContext createContext(
            ChatRequest request,
            AgentProgressReporter progressReporter
    ) {
        ChatAttachmentContext attachmentContext =
                chatAttachmentContextFactory.create(
                        request
                );

        ChatMessageContext messageContext =
                chatMessageContextFactory.createContext(
                        request.getRoomId(),
                        request.isRegenerate(),
                        attachmentContext.documentFileIds(),
                        attachmentContext.encodedImages(),
                        progressReporter
                );

        List<ChatModelMessage> messages =
                messageContext.messages();

        List<ChatModelTool> tools =
                toolRegistry.getSpecifications()
                        .stream()
                        .map(
                                chatModelToolMapper::map
                        )
                        .toList();

        ChatModelType modelType =
                attachmentContext.hasImages()
                        ? ChatModelType.VISION
                        : ChatModelType.TEXT;

        log.info(
                "AI 요청 생성 완료. roomId={}, modelType={}, documentCount={}, imageCount={}, sourceCount={}",
                request.getRoomId(),
                modelType,
                attachmentContext.documentFileIds().size(),
                attachmentContext.encodedImages().size(),
                messageContext.sources().size()
        );

        ChatModelRequest modelRequest =
                new ChatModelRequest(
                        modelType,
                        messages,
                        tools
                );

        return new ChatExecutionContext(
                modelRequest,
                messageContext.sources(),
                attachmentContext.documentFileIds()
        );
    }
}