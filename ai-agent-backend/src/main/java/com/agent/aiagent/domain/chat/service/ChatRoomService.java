package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatMessageResponse;
import com.agent.aiagent.domain.chat.dto.ChatRoomCreateRequest;
import com.agent.aiagent.domain.chat.dto.ChatRoomResponse;
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