package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.rag.model.RetrievedChunk;
import com.agent.aiagent.provider.embedding.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingFileChunkSearchService {

    private static final double EMBEDDING_WEIGHT = 0.7;
    private static final double KEYWORD_WEIGHT = 0.3;

    private static final double MIN_EMBEDDING_SCORE = 0.43;
    private static final double MIN_KEYWORD_SCORE = 0.70;
    private final ChatFileChunkRepository chatFileChunkRepository;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingJsonConverter embeddingJsonConverter;

    public List<RetrievedChunk> search(
            String roomId,
            List<String> fileIds,
            String question,
            int topK
    ) {
        if (!StringUtils.hasText(roomId)) {
            return List.of();
        }

        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }

        if (!StringUtils.hasText(question)) {
            return List.of();
        }

        if (topK <= 0) {
            return List.of();
        }

        List<List<Double>> questionEmbeddings =
                embeddingProvider.embed(
                        List.of(question)
                );

        if (
                questionEmbeddings == null
                        || questionEmbeddings.isEmpty()
        ) {
            throw new IllegalStateException(
                    "질문 임베딩을 생성하지 못했습니다."
            );
        }

        List<Double> questionEmbedding =
                questionEmbeddings.get(0);

        List<ChatFileChunk> chunks =
                chatFileChunkRepository
                        .findAllByRoomIdAndFileIdInOrderByFileIdAscChunkIndexAsc(
                                roomId,
                                fileIds
                        );

        List<RetrievedChunk> scoredChunks =
                chunks.stream()
                        .map(chunk ->
                                calculateChunkScore(
                                        chunk,
                                        question,
                                        questionEmbedding
                                )
                        )
                        /*
                         * 코드/설정 검색에서는 식별자, 파일명, SOURCE_PATH 같은
                         * literal match를 embedding 유사도보다 먼저 본다.
                         * 특정 언어/확장자에는 가중치를 주지 않는다.
                         */
                        .sorted(
                                Comparator
                                        .comparingInt(
                                                (RetrievedChunk result) ->
                                                        calculateLiteralMatchPriority(
                                                                question,
                                                                result.chunk()
                                                                        .getContent()
                                                        )
                                        )
                                        .reversed()
                                        .thenComparing(
                                                Comparator.comparingDouble(
                                                        RetrievedChunk::finalScore
                                                ).reversed()
                                        )
                        )
                        .toList();

        scoredChunks.forEach(result ->
                log.trace(
                        "계산 chunk. fileId={}, chunkIndex={}, embeddingScore={}, keywordScore={}, finalScore={}",
                        result.chunk().getFileId(),
                        result.chunk().getChunkIndex(),
                        formatScore(result.embeddingScore()),
                        formatScore(result.keywordScore()),
                        formatScore(result.finalScore())
                )
        );

        List<RetrievedChunk> retrievedChunks =
                scoredChunks.stream()
//                        .filter(result ->
//                                result.embeddingScore() >= MIN_EMBEDDING_SCORE
//                                        || result.keywordScore() >= MIN_KEYWORD_SCORE
//                        )
                        .limit(topK)
                        .toList();

        log.info(
                "Hybrid chunk 검색 완료. roomId={}, fileCount={}, chunkCount={}, selectedChunkCount={}, topK={}",
                roomId,
                fileIds.size(),
                chunks.size(),
                retrievedChunks.size(),
                topK
        );

        retrievedChunks.forEach(result ->
                log.debug(
                        "선택 chunk. fileId={}, chunkIndex={}, embeddingScore={}, keywordScore={}, finalScore={}",
                        result.chunk().getFileId(),
                        result.chunk().getChunkIndex(),
                        formatScore(
                                result.embeddingScore()
                        ),
                        formatScore(
                                result.keywordScore()
                        ),
                        formatScore(
                                result.finalScore()
                        )
                )
        );

        return retrievedChunks;
    }

    private RetrievedChunk calculateChunkScore(
            ChatFileChunk chunk,
            String question,
            List<Double> questionEmbedding
    ) {
        List<Double> chunkEmbedding =
                embeddingJsonConverter.deserialize(
                        chunk.getEmbedding()
                );

        double embeddingScore =
                cosineSimilarity(
                        questionEmbedding,
                        chunkEmbedding
                );

        double keywordScore =
                calculateKeywordScore(
                        question,
                        chunk.getContent()
                );

        double finalScore =
                embeddingScore * EMBEDDING_WEIGHT
                        + keywordScore * KEYWORD_WEIGHT;

        return new RetrievedChunk(
                chunk,
                embeddingScore,
                keywordScore,
                finalScore
        );
    }

    /**
     * literal 근거를 hybrid score보다 먼저 비교한다.
     *
     * 4: SOURCE_PATH 또는 파일명 직접 일치
     * 3: 원문에 검색어 그대로 포함
     * 2: 구두점을 제거한 검색어 포함
     * 1: 검색 keyword가 모두 포함
     * 0: literal 근거 없음
     */
    private int calculateLiteralMatchPriority(
            String question,
            String content
    ) {
        if (
                !StringUtils.hasText(question)
                        || !StringUtils.hasText(content)
        ) {
            return 0;
        }

        String normalizedQuestion =
                normalizePathLikeText(
                        question
                );

        String sourcePath =
                extractSourcePath(
                        content
                );

        if (StringUtils.hasText(sourcePath)) {
            String normalizedSourcePath =
                    normalizePathLikeText(
                            sourcePath
                    );

            String sourceFileName =
                    getFileName(
                            normalizedSourcePath
                    );

            String queryFileName =
                    getFileName(
                            normalizedQuestion
                    );

            if (
                    normalizedSourcePath.equals(
                            normalizedQuestion
                    )
                            || normalizedSourcePath.endsWith(
                            "/" + normalizedQuestion
                    )
                            || (
                            StringUtils.hasText(queryFileName)
                                    && sourceFileName.equals(
                                    queryFileName
                            )
                    )
            ) {
                return 4;
            }
        }

        String lowerQuestion =
                question.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        String lowerContent =
                content.toLowerCase(
                        Locale.ROOT
                );

        if (
                StringUtils.hasText(lowerQuestion)
                        && lowerContent.contains(
                        lowerQuestion
                )
        ) {
            return 3;
        }

        String compactQuestion =
                normalizeIdentifierText(
                        question
                );

        String compactContent =
                normalizeIdentifierText(
                        content
                );

        if (
                compactQuestion.length() >= 2
                        && compactContent.contains(
                        compactQuestion
                )
        ) {
            return 2;
        }

        Set<String> keywords =
                extractKeywords(
                        question
                );

        String normalizedContent =
                normalizeText(
                        content
                );

        if (
                !keywords.isEmpty()
                        && keywords.stream()
                        .allMatch(
                                normalizedContent::contains
                        )
        ) {
            return 1;
        }

        return 0;
    }

    private String extractSourcePath(
            String content
    ) {
        if (!StringUtils.hasText(content)) {
            return null;
        }

        for (
                String line :
                content.split(
                        "\\R"
                )
        ) {
            String trimmed =
                    line.trim();

            if (!trimmed.startsWith("SOURCE_PATH:")) {
                continue;
            }

            String sourcePath =
                    trimmed.substring(
                                    "SOURCE_PATH:".length()
                            )
                            .trim();

            return sourcePath.isBlank()
                    ? null
                    : sourcePath;
        }

        return null;
    }

    private String normalizePathLikeText(
            String value
    ) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value.trim()
                .replace(
                        '\\',
                        '/'
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private String normalizeIdentifierText(
            String value
    ) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value.toLowerCase(
                        Locale.ROOT
                )
                .replaceAll(
                        "[^0-9a-z가-힣_$]+",
                        ""
                );
    }

    private String getFileName(
            String path
    ) {
        if (!StringUtils.hasText(path)) {
            return "";
        }

        String normalized =
                path.replace(
                        '\\',
                        '/'
                );

        int separatorIndex =
                normalized.lastIndexOf(
                        '/'
                );

        return separatorIndex < 0
                ? normalized
                : normalized.substring(
                separatorIndex + 1
        );
    }

    private double calculateKeywordScore(
            String question,
            String content
    ) {
        if (
                !StringUtils.hasText(question)
                        || !StringUtils.hasText(content)
        ) {
            return 0.0;
        }

        Set<String> questionKeywords =
                extractKeywords(
                        question
                );

        if (questionKeywords.isEmpty()) {
            return 0.0;
        }

        String normalizedContent =
                normalizeText(
                        content
                );

        long matchedKeywordCount =
                questionKeywords.stream()
                        .filter(normalizedContent::contains)
                        .count();

        return (double) matchedKeywordCount
                / questionKeywords.size();
    }

    private Set<String> extractKeywords(
            String text
    ) {
        String normalizedText =
                normalizeText(
                        text
                );

        return Arrays.stream(
                        normalizedText.split("\\s+")
                )
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(keyword ->
                        keyword.length() >= 2
                )
                .flatMap(keyword ->
                        Arrays.stream(
                                new String[]{
                                        keyword,
                                        removeKoreanPostposition(
                                                keyword
                                        )
                                }
                        )
                )
                .filter(StringUtils::hasText)
                .filter(keyword ->
                        keyword.length() >= 2
                )
                .collect(
                        Collectors.toCollection(
                                LinkedHashSet::new
                        )
                );
    }

    private String normalizeText(
            String text
    ) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        return text
                .toLowerCase(
                        Locale.ROOT
                )
                .replaceAll(
                        "[^0-9a-z가-힣]+",
                        " "
                )
                .replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }

    private String removeKoreanPostposition(
            String keyword
    ) {
        List<String> postpositions =
                List.of(
                        "으로부터",
                        "에게서",
                        "에서는",
                        "으로는",
                        "에게는",
                        "이라도",
                        "이라고",
                        "에서",
                        "에게",
                        "한테",
                        "으로",
                        "이랑",
                        "랑",
                        "까지",
                        "부터",
                        "보다",
                        "처럼",
                        "하고",
                        "과",
                        "와",
                        "은",
                        "는",
                        "이",
                        "가",
                        "을",
                        "를",
                        "에",
                        "의",
                        "도",
                        "만"
                );

        for (String postposition : postpositions) {
            if (
                    keyword.endsWith(postposition)
                            && keyword.length() > postposition.length() + 1
            ) {
                return keyword.substring(
                        0,
                        keyword.length() - postposition.length()
                );
            }
        }

        return keyword;
    }

    private double cosineSimilarity(
            List<Double> vectorA,
            List<Double> vectorB
    ) {
        if (
                vectorA == null
                        || vectorB == null
                        || vectorA.isEmpty()
                        || vectorB.isEmpty()
                        || vectorA.size() != vectorB.size()
        ) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (
                int index = 0;
                index < vectorA.size();
                index++
        ) {
            double valueA =
                    vectorA.get(index);

            double valueB =
                    vectorB.get(index);

            dotProduct +=
                    valueA * valueB;

            normA +=
                    valueA * valueA;

            normB +=
                    valueB * valueB;
        }

        if (
                normA == 0.0
                        || normB == 0.0
        ) {
            return 0.0;
        }

        return dotProduct
                / (
                Math.sqrt(normA)
                        * Math.sqrt(normB)
        );
    }

    private String formatScore(
            double score
    ) {
        return String.format(
                Locale.ROOT,
                "%.4f",
                score
        );
    }
}