package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatAttachmentContext;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.file.service.ChatFileService;
import com.agent.aiagent.domain.rag.service.RagPromptBuilder;
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

    private static final String USER_ROLE = "user";

    private final ChatFileService chatFileService;
    private final ChatFileRepository chatFileRepository;
    private final ConversationSummaryService conversationSummaryService;
    private final ChatImageEncoder chatImageEncoder;
    private final RagPromptBuilder ragPromptBuilder;
    private final ChatAttachmentContextFactory chatAttachmentContextFactory;

    public ChatModelRequest create(
            ChatRequest request
    ) {
        ChatAttachmentContext attachmentContext =
                chatAttachmentContextFactory.create(
                        request
                );

        List<ChatModelMessage> messages =
                createMessages(
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
                messages
        );
    }

    private List<ChatModelMessage> createMessages(
            String roomId,
            boolean regenerate,
            List<String> documentFileIds,
            List<String> encodedImages
    ) {
        List<ChatModelMessage> messages =
                conversationSummaryService.createConversationContext(
                        roomId,
                        regenerate
                );

        if (
                documentFileIds.isEmpty()
                        && encodedImages.isEmpty()
        ) {
            return messages;
        }

        for (
                int index = messages.size() - 1;
                index >= 0;
                index--
        ) {
            ChatModelMessage message =
                    messages.get(index);

            if (!USER_ROLE.equalsIgnoreCase(message.getRole())) {
                continue;
            }

            String content =
                    ragPromptBuilder.build(
                            roomId,
                            documentFileIds,
                            messages,
                            message.getContent()
                    );

            List<String> images =
                    encodedImages.isEmpty()
                            ? null
                            : encodedImages;

            messages.set(
                    index,
                    new ChatModelMessage(
                            message.getRole(),
                            content,
                            images
                    )
            );

            break;
        }

        log.info(
                "대화 메모리 기반 AI 메시지 생성 완료. roomId={}, messageCount={}, regenerate={}",
                roomId,
                messages.size(),
                regenerate
        );

        return messages;
    }
}