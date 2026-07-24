package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.rag.service.RagPromptBuilder;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageContextFactory {

    private static final String USER_ROLE = "user";

    private final ConversationSummaryService conversationSummaryService;
    private final RagPromptBuilder ragPromptBuilder;

    public List<ChatModelMessage> create(
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

        applyAttachmentsToLastUserMessage(
                roomId,
                messages,
                documentFileIds,
                encodedImages
        );

        log.info(
                "채팅 메시지 컨텍스트 생성 완료. roomId={}, messageCount={}, regenerate={}, documentCount={}, imageCount={}",
                roomId,
                messages.size(),
                regenerate,
                documentFileIds.size(),
                encodedImages.size()
        );

        return messages;
    }

    private void applyAttachmentsToLastUserMessage(
            String roomId,
            List<ChatModelMessage> messages,
            List<String> documentFileIds,
            List<String> encodedImages
    ) {
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

            return;
        }

        log.warn(
                "첨부파일을 적용할 사용자 메시지를 찾지 못했습니다. roomId={}",
                roomId
        );
    }
}