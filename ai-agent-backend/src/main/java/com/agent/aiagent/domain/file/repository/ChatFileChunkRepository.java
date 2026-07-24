package com.agent.aiagent.domain.file.repository;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatFileChunkRepository
        extends JpaRepository<ChatFileChunk, String> {

    List<ChatFileChunk> findAllByRoomIdAndFileIdInOrderByFileIdAscChunkIndexAsc(
            String roomId,
            List<String> fileIds
    );

    void deleteAllByFileId(
            String fileId
    );
}