package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatAttachmentContext;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
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

    public ChatModelRequest create(
            ChatRequest request
    ) {
        ChatAttachmentContext attachmentContext =
                chatAttachmentContextFactory.create(
                        request
                );

        List<ChatModelMessage> messages =
                chatMessageContextFactory.create(
                        request.getRoomId(),
                        request.isRegenerate(),
                        attachmentContext.documentFileIds(),
                        attachmentContext.encodedImages()
                );

        ChatModelType modelType =
                attachmentContext.hasImages()
                        ? ChatModelType.VISION
                        : ChatModelType.TEXT;

        log.info(
                "AI 요청 생성 완료. roomId={}, modelType={}, documentCount={}, imageCount={}",
                request.getRoomId(),
                modelType,
                attachmentContext.documentFileIds().size(),
                attachmentContext.encodedImages().size()
        );

        return new ChatModelRequest(
                modelType,
                messages,
                List.of()
        );
    }

}