package com.agent.aiagent.domain.file.repository;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatFileChunkRepository
        extends JpaRepository<ChatFileChunk, String> {

    List<ChatFileChunk> findAllByRoomIdAndFileIdInOrderByFileIdAscChunkIndexAsc(
            String roomId,
            List<String> fileIds
    );

    @Query("""
            select c from ChatFileChunk c
            where c.roomId = :roomId
              and c.fileId in :fileIds
              and c.startMillis is not null
              and c.endMillis is not null
              and c.startMillis < :endMillis
              and c.endMillis > :startMillis
            order by c.startMillis asc, c.chunkIndex asc
            """)
    List<ChatFileChunk> findVideoChunksInTimeRange(
            @Param("roomId") String roomId,
            @Param("fileIds") List<String> fileIds,
            @Param("startMillis") long startMillis,
            @Param("endMillis") long endMillis
    );

    void deleteAllByFileId(
            String fileId
    );

    List<ChatFileChunk> findAllByFileId(String fileId);
}