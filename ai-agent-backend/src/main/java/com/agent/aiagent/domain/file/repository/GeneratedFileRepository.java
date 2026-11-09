package com.agent.aiagent.domain.file.repository;

import com.agent.aiagent.domain.file.entity.GeneratedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeneratedFileRepository
        extends JpaRepository<GeneratedFile, String> {

    List<GeneratedFile> findAllByMessageIdOrderByCreatedAtAsc(
            String messageId
    );

    List<GeneratedFile> findAllByRoomIdAndMessageIdIsNullOrderByCreatedAtAsc(
            String roomId
    );

    Optional<GeneratedFile> findByIdAndRoomId(
            String id,
            String roomId
    );

    List<GeneratedFile> findAllByRoomIdOrderByCreatedAtAsc(
            String roomId
    );
}