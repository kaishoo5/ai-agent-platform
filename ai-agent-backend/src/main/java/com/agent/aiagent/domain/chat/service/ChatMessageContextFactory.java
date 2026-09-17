package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.model.ChatMessageContext;
import com.agent.aiagent.domain.rag.model.RagPromptResult;
import com.agent.aiagent.domain.rag.service.RagPromptBuilder;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

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
        return createContext(
                roomId,
                regenerate,
                documentFileIds,
                encodedImages
        ).messages();
    }

    public ChatMessageContext createContext(
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
            return new ChatMessageContext(
                    messages,
                    List.of()
            );
        }

        RagPromptResult ragPromptResult =
                applyAttachmentsToLastUserMessage(
                        roomId,
                        messages,
                        documentFileIds,
                        encodedImages
                );

        log.info(
                "채팅 메시지 컨텍스트 생성 완료. roomId={}, messageCount={}, regenerate={}, documentCount={}, imageCount={}, sourceCount={}",
                roomId,
                messages.size(),
                regenerate,
                documentFileIds.size(),
                encodedImages.size(),
                ragPromptResult.sources().size()
        );

        return new ChatMessageContext(
                messages,
                ragPromptResult.sources()
        );
    }

    private RagPromptResult applyAttachmentsToLastUserMessage(
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

            RagPromptResult ragPromptResult =
                    ragPromptBuilder.buildResult(
                            roomId,
                            documentFileIds,
                            messages,
                            message.getContent()
                    );

            String content =
                    ragPromptResult.prompt();

            if (!documentFileIds.isEmpty()) {
                content =
                        content
                                + System.lineSeparator()
                                + System.lineSeparator()
                                + "[현재 첨부 파일]"
                                + System.lineSeparator()
                                + documentFileIds.stream()
                                .map(fileId ->
                                        "- fileId: " + fileId
                                )
                                .collect(
                                        Collectors.joining(
                                                System.lineSeparator()
                                        )
                                );
            }

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

            return ragPromptResult;
        }

        log.warn(
                "첨부파일을 적용할 사용자 메시지를 찾지 못했습니다. roomId={}",
                roomId
        );

        return RagPromptResult.withoutSources(
                ""
        );
    }
}