package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.*;
import com.agent.aiagent.domain.chat.entity.ChatMessage;
import com.agent.aiagent.domain.chat.entity.ChatRoom;
import com.agent.aiagent.domain.chat.repository.ChatMessageRepository;
import com.agent.aiagent.domain.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatRoomResponse createRoom(ChatRoomCreateRequest request) {
        String title = request == null
                ? "새 채팅"
                : normalizeTitle(request.title());

        ChatRoom chatRoom = new ChatRoom(title);
        ChatRoom savedChatRoom = chatRoomRepository.save(chatRoom);

        return ChatRoomResponse.from(savedChatRoom);
    }

    public List<ChatRoomResponse> getRooms() {
        return chatRoomRepository
                .findAllByOrderByUpdatedAtDesc()
                .stream()
                .map(ChatRoomResponse::from)
                .toList();
    }

    public ChatRoomResponse getRoom(String roomId) {
        ChatRoom chatRoom = findRoom(roomId);

        return ChatRoomResponse.from(chatRoom);
    }

    public List<ChatMessageResponse> getMessages(String roomId) {
        findRoom(roomId);

        return chatMessageRepository
                .findAllByRoomIdOrderByCreatedAtAsc(roomId)
                .stream()
                .map(ChatMessageResponse::from)
                .toList();
    }

    @Transactional
    public List<ChatMessageResponse> editUserMessage(
            String roomId,
            String messageId,
            ChatMessageEditRequest request
    ) {
        ChatRoom chatRoom =
                findRoom(
                        roomId
                );

        String content =
                request == null
                        ? null
                        : request.content();

        if (
                content == null
                        || content.isBlank()
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "수정할 메시지 내용이 없습니다."
            );
        }

        List<ChatMessage> messages =
                chatMessageRepository
                        .findAllByRoomIdOrderByCreatedAtAsc(
                                roomId
                        );

        int messageIndex =
                -1;

        for (
                int index = 0;
                index < messages.size();
                index++
        ) {
            if (
                    messageId.equals(
                            messages.get(index).getId()
                    )
            ) {
                messageIndex =
                        index;

                break;
            }
        }

        if (messageIndex < 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "수정할 메시지를 찾을 수 없습니다."
            );
        }

        ChatMessage targetMessage =
                messages.get(
                        messageIndex
                );

        if (
                !"user".equalsIgnoreCase(
                        targetMessage.getRole()
                )
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "사용자 메시지만 수정할 수 있습니다."
            );
        }

        targetMessage.updateContent(
                content.trim()
        );

        if (
                messageIndex + 1
                        < messages.size()
        ) {
            List<ChatMessage> messagesToDelete =
                    List.copyOf(
                            messages.subList(
                                    messageIndex + 1,
                                    messages.size()
                            )
                    );

            chatMessageRepository.deleteAll(
                    messagesToDelete
            );
        }

        chatRoom.clearSummary();
        chatRoom.touch();

        return chatMessageRepository
                .findAllByRoomIdOrderByCreatedAtAsc(
                        roomId
                )
                .stream()
                .map(ChatMessageResponse::from)
                .toList();
    }

    @Transactional
    public ChatRoomResponse updateTitle(
            String roomId,
            ChatRoomTitleUpdateRequest request
    ) {
        ChatRoom chatRoom = findRoom(roomId);

        String title = normalizeTitle(
                request == null
                        ? null
                        : request.title()
        );

        chatRoom.updateTitle(title);

        return ChatRoomResponse.from(chatRoom);
    }

    @Transactional
    public void deleteRoom(String roomId) {
        ChatRoom chatRoom = findRoom(roomId);

        chatRoomRepository.delete(chatRoom);
    }

    private ChatRoom findRoom(String roomId) {
        return chatRoomRepository
                .findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "채팅방을 찾을 수 없습니다."
                ));
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "새 채팅";
        }

        String normalizedTitle = title.trim();

        if (normalizedTitle.length() <= 200) {
            return normalizedTitle;
        }

        return normalizedTitle.substring(0, 200);
    }
}
