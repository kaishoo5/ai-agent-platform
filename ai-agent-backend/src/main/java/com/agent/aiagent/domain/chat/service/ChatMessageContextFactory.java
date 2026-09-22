package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.model.ChatMessageContext;
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
        return createContext(
                roomId,
                regenerate,
                documentFileIds,
                encodedImages,
                null
        );
    }

    public ChatMessageContext createContext(
            String roomId,
            boolean regenerate,
            List<String> documentFileIds,
            List<String> encodedImages,
            AgentProgressReporter progressReporter
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

        return new ChatMessageContext(
                messages,
                List.of()
        );
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
                    message.getContent();

            if (!documentFileIds.isEmpty()) {
                content =
                        content
                                + System.lineSeparator()
                                + System.lineSeparator()
                                + """
                                [현재 첨부 파일]
                                다음 fileId들은 현재 대화에 첨부된 파일입니다.
                                첨부파일의 실제 내용이 필요한 경우 제공된 첨부파일 검색 도구를 사용하세요.
                                파일에 대한 작업을 요청받은 경우 요청에 적합한 도구를 선택하세요.
                                """
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

            log.info(
                    "첨부파일 컨텍스트 적용 완료. roomId={}, documentCount={}, imageCount={}",
                    roomId,
                    documentFileIds.size(),
                    encodedImages.size()
            );

            return;
        }

        log.warn(
                "첨부파일을 적용할 사용자 메시지를 찾지 못했습니다. roomId={}",
                roomId
        );
    }
}