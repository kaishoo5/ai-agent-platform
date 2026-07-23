package com.agent.aiagent.domain.chat.repository;

import com.agent.aiagent.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findAllByRoomIdOrderByCreatedAtAsc(String roomId);

    void deleteAllByRoomId(String roomId);

    boolean existsByRoomIdAndRole(
            String roomId,
            String role
    );

    Optional<ChatMessage> findFirstByRoomIdAndRoleOrderByCreatedAtDesc(
            String roomId,
            String role
    );
}