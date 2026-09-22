package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.VideoTranscriptSegmentEntity;
import com.agent.aiagent.domain.file.repository.VideoTranscriptSegmentRepository;
import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.model.VideoTranscriptSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTranscriptSegmentService {

    private final VideoTranscriptSegmentRepository videoTranscriptSegmentRepository;

    @Transactional
    public void save(
            ChatFile chatFile,
            VideoTranscript transcript
    ) {
        if (chatFile == null) {
            throw new IllegalArgumentException(
                    "chatFile이 없습니다."
            );
        }

        if (
                transcript == null
                        || transcript.segments() == null
        ) {
            videoTranscriptSegmentRepository.deleteAllByFileId(
                    chatFile.getId()
            );

            log.info(
                    "영상 원본 transcript segment 저장을 생략했습니다. fileId={}, reason=emptyTranscript",
                    chatFile.getId()
            );

            return;
        }

        List<VideoTranscriptSegment> segments =
                transcript.segments()
                        .stream()
                        .filter(segment ->
                                segment != null
                                        && StringUtils.hasText(
                                        segment.text()
                                )
                                        && segment.endMillis()
                                        > segment.startMillis()
                        )
                        .toList();

        videoTranscriptSegmentRepository.deleteAllByFileId(
                chatFile.getId()
        );

        if (segments.isEmpty()) {
            log.info(
                    "영상 원본 transcript segment 저장을 생략했습니다. fileId={}, reason=emptySegments",
                    chatFile.getId()
            );

            return;
        }

        LocalDateTime createdAt =
                LocalDateTime.now();

        List<VideoTranscriptSegmentEntity> entities =
                new ArrayList<>(
                        segments.size()
                );

        for (
                int index = 0;
                index < segments.size();
                index++
        ) {
            VideoTranscriptSegment segment =
                    segments.get(
                            index
                    );

            VideoTranscriptSegmentEntity entity =
                    VideoTranscriptSegmentEntity.builder()
                            .id(
                                    UUID.randomUUID()
                                            .toString()
                            )
                            .fileId(
                                    chatFile.getId()
                            )
                            .segmentIndex(
                                    index
                            )
                            .startMillis(
                                    segment.startMillis()
                            )
                            .endMillis(
                                    segment.endMillis()
                            )
                            .text(
                                    segment.text()
                                            .trim()
                            )
                            .createdAt(
                                    createdAt
                            )
                            .build();

            entities.add(
                    entity
            );
        }

        videoTranscriptSegmentRepository.saveAll(
                entities
        );

        log.info(
                "영상 원본 transcript segment 저장 완료. roomId={}, fileId={}, fileName={}, segmentCount={}",
                chatFile.getRoomId(),
                chatFile.getId(),
                chatFile.getOriginalName(),
                entities.size()
        );
    }

    @Transactional(readOnly = true)
    public List<VideoTranscriptSegmentEntity> findOverlapping(
            String fileId,
            long startMillis,
            long endMillis
    ) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException(
                    "fileId가 없습니다."
            );
        }

        if (startMillis < 0) {
            throw new IllegalArgumentException(
                    "startMillis는 0 이상이어야 합니다."
            );
        }

        if (endMillis <= startMillis) {
            throw new IllegalArgumentException(
                    "endMillis는 startMillis보다 커야 합니다."
            );
        }

        List<VideoTranscriptSegmentEntity> segments =
                videoTranscriptSegmentRepository.findAllOverlapping(
                        fileId,
                        startMillis,
                        endMillis
                );

        log.info(
                "영상 transcript segment 구간 조회 완료. fileId={}, startMillis={}, endMillis={}, segmentCount={}",
                fileId,
                startMillis,
                endMillis,
                segments.size()
        );

        return segments;
    }
}