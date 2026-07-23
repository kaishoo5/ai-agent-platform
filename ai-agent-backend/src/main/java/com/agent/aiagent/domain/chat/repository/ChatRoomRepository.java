package com.agent.aiagent.domain.chat.repository;

import com.agent.aiagent.domain.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {

    List<ChatRoom> findAllByOrderByUpdatedAtDesc();
}