package com.agent.aiagent.domain.file.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@Entity
@Table(
        name = "chat_video_transcript_segment",
        indexes = {
                @Index(
                        name = "idx_video_transcript_segment_file_id",
                        columnList = "file_id"
                ),
                @Index(
                        name = "idx_video_transcript_segment_file_time",
                        columnList = "file_id, start_millis, end_millis"
                )
        }
)
@NoArgsConstructor(
        access = AccessLevel.PROTECTED
)
@AllArgsConstructor
public class VideoTranscriptSegmentEntity {

    @Id
    @Column(
            length = 36,
            nullable = false
    )
    private String id;

    @Column(
            name = "file_id",
            length = 36,
            nullable = false
    )
    private String fileId;

    @Column(
            name = "segment_index",
            nullable = false
    )
    private int segmentIndex;

    @Column(
            name = "start_millis",
            nullable = false
    )
    private long startMillis;

    @Column(
            name = "end_millis",
            nullable = false
    )
    private long endMillis;

    @Column(
            columnDefinition = "LONGTEXT",
            nullable = false
    )
    private String text;

    @Column(
            name = "created_at",
            nullable = false
    )
    private LocalDateTime createdAt;
}