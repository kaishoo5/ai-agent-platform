package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.entity.ChatMessage;
import com.agent.aiagent.domain.chat.entity.ChatRoom;
import com.agent.aiagent.domain.chat.repository.ChatMessageRepository;
import com.agent.aiagent.domain.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private static final String USER_ROLE = "user";
    private static final String ASSISTANT_ROLE = "assistant";

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate transactionTemplate;

    public void saveUserMessage(
            String roomId,
            String content
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom = findRoom(roomId);

            boolean isFirstUserMessage =
                    !chatMessageRepository.existsByRoomIdAndRole(
                            roomId,
                            USER_ROLE
                    );

            ChatMessage chatMessage = new ChatMessage(
                    chatRoom,
                    USER_ROLE,
                    content
            );

            chatMessageRepository.save(chatMessage);

            if (
                    isFirstUserMessage
                            && "새 채팅".equals(chatRoom.getTitle())
            ) {
                chatRoom.changeTitle(
                        createRoomTitle(content)
                );
            }

            chatRoom.touch();
        });
    }

    public void saveAssistantMessage(
            String roomId,
            String content
    ) {
        if (content == null || content.isBlank()) {
            log.warn(
                    "저장할 AI 응답이 비어 있습니다. roomId={}",
                    roomId
            );

            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom = findRoom(roomId);

            ChatMessage chatMessage = new ChatMessage(
                    chatRoom,
                    ASSISTANT_ROLE,
                    content
            );

            chatMessageRepository.save(chatMessage);
            chatRoom.touch();
        });
    }

    public void replaceLastAssistantMessage(
            String roomId,
            String content
    ) {
        if (content == null || content.isBlank()) {
            log.warn(
                    "교체할 AI 응답이 비어 있습니다. roomId={}",
                    roomId
            );

            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            ChatRoom chatRoom = findRoom(roomId);

            chatMessageRepository
                    .findFirstByRoomIdAndRoleOrderByCreatedAtDesc(
                            roomId,
                            ASSISTANT_ROLE
                    )
                    .ifPresent(chatMessageRepository::delete);

            ChatMessage newAssistantMessage = new ChatMessage(
                    chatRoom,
                    ASSISTANT_ROLE,
                    content
            );

            chatMessageRepository.save(newAssistantMessage);
            chatRoom.touch();
        });
    }

    public void saveInterruptedAssistantMessage(
            ChatRequest request,
            String roomId,
            StringBuilder assistantContent,
            AtomicBoolean responseSaved
    ) {
        if (request.isRegenerate()) {
            return;
        }

        if (!responseSaved.compareAndSet(false, true)) {
            return;
        }

        String content = assistantContent.length() > 0
                ? assistantContent.toString()
                : "응답이 중단되었습니다.";

        saveAssistantMessage(
                roomId,
                content
        );

        log.info(
                "중단된 AI 응답을 저장했습니다. roomId={}, contentLength={}",
                roomId,
                content.length()
        );
    }

    private ChatRoom findRoom(String roomId) {
        return chatRoomRepository
                .findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "채팅방을 찾을 수 없습니다."
                ));
    }

    private String createRoomTitle(
            String content
    ) {
        String normalizedContent = content
                .replaceAll("\\s+", " ")
                .trim();

        if (normalizedContent.length() <= 25) {
            return normalizedContent;
        }

        return normalizedContent.substring(0, 25) + "...";
    }
}