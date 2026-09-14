package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.file.service.extractor.FileContentExtractorManager;
import com.agent.aiagent.domain.video.model.VideoTranscript;
import com.agent.aiagent.domain.video.model.VideoTranscriptSegment;
import com.agent.aiagent.provider.embedding.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFileChunkService {

    private static final int VIDEO_EMBEDDING_BATCH_SIZE = 20;
    private static final long VIDEO_CHUNK_MAX_DURATION_MILLIS = 30_000L;

    private final FileChunkService fileChunkService;
    private final FileContentExtractorManager fileContentExtractorManager;
    private final EmbeddingProvider embeddingProvider;
    private final ChatFileChunkRepository chatFileChunkRepository;
    private final EmbeddingJsonConverter embeddingJsonConverter;

    @Transactional
    public void saveChunks(
            ChatFile chatFile
    ) {
        String fileContent =
                fileContentExtractorManager.extract(
                        chatFile
                );

        List<String> chunks =
                fileChunkService.split(
                        fileContent
                );

        if (chunks.isEmpty()) {
            log.info(
                    "파일 chunk 저장을 생략했습니다. fileId={}, fileName={}, reason=emptyContent",
                    chatFile.getId(),
                    chatFile.getOriginalName()
            );

            return;
        }

        List<List<Double>> embeddings =
                embeddingProvider.embed(
                        chunks
                );

        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException(
                    "chunk 개수와 embedding 개수가 일치하지 않습니다."
            );
        }

        List<ChatFileChunk> entities =
                new ArrayList<>();

        LocalDateTime createdAt =
                LocalDateTime.now();

        for (
                int index = 0;
                index < chunks.size();
                index++
        ) {
            ChatFileChunk entity =
                    ChatFileChunk.builder()
                            .id(
                                    UUID.randomUUID()
                                            .toString()
                            )
                            .fileId(
                                    chatFile.getId()
                            )
                            .roomId(
                                    chatFile.getRoomId()
                            )
                            .chunkIndex(
                                    index
                            )
                            .content(
                                    chunks.get(index)
                            )
                            .embedding(
                                    embeddingJsonConverter.serialize(
                                            embeddings.get(index)
                                    )
                            )
                            .createdAt(
                                    createdAt
                            )
                            .build();

            entities.add(
                    entity
            );
        }

        chatFileChunkRepository.deleteAllByFileId(
                chatFile.getId()
        );

        chatFileChunkRepository.saveAll(
                entities
        );

        log.info(
                "파일 chunk 저장 완료. roomId={}, fileId={}, fileName={}, chunkCount={}",
                chatFile.getRoomId(),
                chatFile.getId(),
                chatFile.getOriginalName(),
                entities.size()
        );
    }

    @Transactional
    public void saveVideoTranscriptChunks(
            ChatFile chatFile,
            VideoTranscript transcript
    ) {
        List<VideoTranscriptSegment> segments =
                transcript.segments()
                        .stream()
                        .filter(segment ->
                                segment.text() != null
                                        && !segment.text().isBlank()
                        )
                        .toList();

        if (segments.isEmpty()) {
            log.info(
                    "영상 transcript chunk 저장을 생략했습니다. fileId={}, fileName={}, reason=emptyTranscript",
                    chatFile.getId(),
                    chatFile.getOriginalName()
            );

            return;
        }

        List<VideoTranscriptSegment> mergedSegments =
                mergeVideoTranscriptSegments(
                        segments
                );

        List<String> contents =
                mergedSegments.stream()
                        .map(VideoTranscriptSegment::text)
                        .toList();

        List<List<Double>> embeddings =
                embedVideoContents(
                        contents
                );

        if (embeddings.size() != mergedSegments.size()) {
            throw new IllegalStateException(
                    "영상 transcript chunk 개수와 embedding 개수가 일치하지 않습니다."
            );
        }

        LocalDateTime createdAt =
                LocalDateTime.now();

        List<ChatFileChunk> entities =
                new ArrayList<>();

        for (
                int index = 0;
                index < mergedSegments.size();
                index++
        ) {
            VideoTranscriptSegment segment =
                    mergedSegments.get(index);

            ChatFileChunk entity =
                    ChatFileChunk.builder()
                            .id(
                                    UUID.randomUUID()
                                            .toString()
                            )
                            .fileId(
                                    chatFile.getId()
                            )
                            .roomId(
                                    chatFile.getRoomId()
                            )
                            .chunkIndex(
                                    index
                            )
                            .content(
                                    segment.text()
                            )
                            .embedding(
                                    embeddingJsonConverter.serialize(
                                            embeddings.get(index)
                                    )
                            )
                            .startMillis(
                                    segment.startMillis()
                            )
                            .endMillis(
                                    segment.endMillis()
                            )
                            .createdAt(
                                    createdAt
                            )
                            .build();

            entities.add(
                    entity
            );
        }

        chatFileChunkRepository.deleteAllByFileId(
                chatFile.getId()
        );

        chatFileChunkRepository.saveAll(
                entities
        );

        log.info(
                "영상 transcript chunk 저장 완료. roomId={}, fileId={}, fileName={}, originalSegmentCount={}, mergedChunkCount={}",
                chatFile.getRoomId(),
                chatFile.getId(),
                chatFile.getOriginalName(),
                segments.size(),
                entities.size()
        );
    }

    private List<VideoTranscriptSegment> mergeVideoTranscriptSegments(
            List<VideoTranscriptSegment> segments
    ) {
        List<VideoTranscriptSegment> mergedSegments =
                new ArrayList<>();

        long currentStartMillis =
                -1L;

        long currentEndMillis =
                -1L;

        StringBuilder currentText =
                new StringBuilder();

        for (
                VideoTranscriptSegment segment : segments
        ) {
            if (currentStartMillis < 0) {
                currentStartMillis =
                        segment.startMillis();

                currentEndMillis =
                        segment.endMillis();

                currentText.append(
                        segment.text()
                );

                continue;
            }

            long mergedDuration =
                    segment.endMillis()
                            - currentStartMillis;

            if (mergedDuration <= VIDEO_CHUNK_MAX_DURATION_MILLIS) {
                currentEndMillis =
                        segment.endMillis();

                if (!currentText.isEmpty()) {
                    currentText.append(
                            System.lineSeparator()
                    );
                }

                currentText.append(
                        segment.text()
                );

                continue;
            }

            mergedSegments.add(
                    new VideoTranscriptSegment(
                            currentStartMillis,
                            currentEndMillis,
                            currentText.toString()
                    )
            );

            currentStartMillis =
                    segment.startMillis();

            currentEndMillis =
                    segment.endMillis();

            currentText =
                    new StringBuilder(
                            segment.text()
                    );
        }

        if (
                currentStartMillis >= 0
                        && !currentText.isEmpty()
        ) {
            mergedSegments.add(
                    new VideoTranscriptSegment(
                            currentStartMillis,
                            currentEndMillis,
                            currentText.toString()
                    )
            );
        }

        log.info(
                "영상 transcript segment 병합 완료. originalCount={}, mergedCount={}",
                segments.size(),
                mergedSegments.size()
        );

        return mergedSegments;
    }

    private List<List<Double>> embedVideoContents(
            List<String> contents
    ) {
        List<List<Double>> embeddings =
                new ArrayList<>();

        for (
                int start = 0;
                start < contents.size();
                start += VIDEO_EMBEDDING_BATCH_SIZE
        ) {
            int end =
                    Math.min(
                            start + VIDEO_EMBEDDING_BATCH_SIZE,
                            contents.size()
                    );

            List<String> batch =
                    contents.subList(
                            start,
                            end
                    );

            log.info(
                    "영상 transcript embedding 처리 중. start={}, end={}, total={}",
                    start,
                    end,
                    contents.size()
            );

            List<List<Double>> batchEmbeddings =
                    embeddingProvider.embed(
                            batch
                    );

            if (batchEmbeddings.size() != batch.size()) {
                throw new IllegalStateException(
                        "영상 transcript batch chunk 개수와 embedding 개수가 일치하지 않습니다."
                );
            }

            embeddings.addAll(
                    batchEmbeddings
            );
        }

        return embeddings;
    }
}