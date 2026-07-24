package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.file.service.extractor.FileContentExtractorManager;
import com.agent.aiagent.infra.ollama.OllamaClient;
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

    private final FileChunkService fileChunkService;
    private final FileContentExtractorManager fileContentExtractorManager;
    private final OllamaClient ollamaClient;
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
                ollamaClient.embed(
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

            entities.add(entity);
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
}