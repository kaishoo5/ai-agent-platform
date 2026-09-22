package com.agent.aiagent.domain.file.repository;

import com.agent.aiagent.domain.file.entity.VideoTranscriptSegmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VideoTranscriptSegmentRepository
        extends JpaRepository<VideoTranscriptSegmentEntity, String> {

    List<VideoTranscriptSegmentEntity> findAllByFileIdOrderBySegmentIndexAsc(
            String fileId
    );

    void deleteAllByFileId(
            String fileId
    );

    @Query("""
            SELECT segment
            FROM VideoTranscriptSegmentEntity segment
            WHERE segment.fileId = :fileId
              AND segment.startMillis < :endMillis
              AND segment.endMillis > :startMillis
            ORDER BY segment.startMillis ASC
            """)
    List<VideoTranscriptSegmentEntity> findAllOverlapping(
            @Param("fileId") String fileId,
            @Param("startMillis") long startMillis,
            @Param("endMillis") long endMillis
    );
}