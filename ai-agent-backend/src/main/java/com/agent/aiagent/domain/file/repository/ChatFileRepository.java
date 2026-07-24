package com.agent.aiagent.domain.file.repository;

import com.agent.aiagent.domain.file.entity.ChatFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatFileRepository
        extends JpaRepository<ChatFile, String> {

    List<ChatFile> findAllByRoomIdOrderByCreatedAtAsc(
            String roomId
    );

    Optional<ChatFile> findByIdAndRoomId(
            String id,
            String roomId
    );
}