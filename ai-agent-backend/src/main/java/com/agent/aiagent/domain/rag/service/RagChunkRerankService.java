package com.agent.aiagent.domain.rag.service;

import com.agent.aiagent.domain.file.service.RetrievedChunk;
import com.agent.aiagent.infra.ollama.OllamaClient;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChunkRerankService {

    private static final int MAX_CHUNK_CONTENT_LENGTH = 1_500;
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\d+");

    private final OllamaClient ollamaClient;

    public List<RetrievedChunk> rerank(
            String question,
            List<RetrievedChunk> candidates,
            int topK
    ) {
        if (!StringUtils.hasText(question)) {
            return limitCandidates(
                    candidates,
                    topK
            );
        }

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (topK <= 0) {
            return List.of();
        }

        if (candidates.size() <= 1) {
            return limitCandidates(
                    candidates,
                    topK
            );
        }

        List<OllamaChatMessage> messages =
                createRerankMessages(
                        question,
                        candidates,
                        topK
                );

        try {
            String response =
                    ollamaClient.chatOnce(
                            OllamaClient.MODEL_TEXT,
                            messages
                    );

            List<Integer> rankedIndexes =
                    parseRankedIndexes(
                            response,
                            candidates.size()
                    );

            List<RetrievedChunk> rerankedChunks =
                    buildRerankedChunks(
                            candidates,
                            rankedIndexes,
                            topK
                    );

            log.info(
                    "RAG Chunk 재정렬 완료. candidateCount={}, selectedCount={}, rankedIndexes={}",
                    candidates.size(),
                    rerankedChunks.size(),
                    rankedIndexes
            );

            return rerankedChunks;
        } catch (Exception exception) {
            log.warn(
                    "RAG Chunk 재정렬에 실패하여 Hybrid 검색 순서를 사용합니다. candidateCount={}, topK={}",
                    candidates.size(),
                    topK,
                    exception
            );

            return limitCandidates(
                    candidates,
                    topK
            );
        }
    }

    private List<OllamaChatMessage> createRerankMessages(
            String question,
            List<RetrievedChunk> candidates,
            int topK
    ) {
        StringBuilder candidatePrompt =
                new StringBuilder();

        for (
                int index = 0;
                index < candidates.size();
                index++
        ) {
            RetrievedChunk candidate =
                    candidates.get(index);

            candidatePrompt.append("[후보 ")
                    .append(index + 1)
                    .append("]\n");

            candidatePrompt.append("파일 ID: ")
                    .append(candidate.chunk().getFileId())
                    .append("\n");

            candidatePrompt.append("Chunk 번호: ")
                    .append(candidate.chunk().getChunkIndex())
                    .append("\n");

            candidatePrompt.append("Hybrid 점수: ")
                    .append(
                            String.format(
                                    "%.4f",
                                    candidate.finalScore()
                            )
                    )
                    .append("\n");

            candidatePrompt.append("내용:\n")
                    .append(
                            limitContent(
                                    candidate.chunk().getContent()
                            )
                    )
                    .append("\n\n");
        }

        String userPrompt =
                """
                다음 사용자 질문에 답변하는 데 관련성이 높은 문서 후보를 순서대로 선택하세요.

                사용자 질문:
                %s

                문서 후보:
                %s

                규칙:
                1. 질문에 직접 답할 수 있는 내용이 포함된 후보를 우선하세요.
                2. 단순 키워드 일치보다 의미적 관련성을 우선하세요.
                3. 관련성이 낮거나 질문과 무관한 후보는 뒤로 보내세요.
                4. 후보 번호만 쉼표로 구분하여 출력하세요.
                5. 설명, 문장, 코드 블록은 출력하지 마세요.
                6. 최대 %d개를 선택하세요.

                출력 예시:
                3,1,5
                """.formatted(
                        question,
                        candidatePrompt,
                        Math.min(
                                topK,
                                candidates.size()
                        )
                );

        return List.of(
                new OllamaChatMessage(
                        "system",
                        """
                        당신은 RAG 검색 결과 재정렬기입니다.
                        사용자 질문과 각 문서 후보의 관련성을 판단하여
                        가장 관련성이 높은 후보 번호를 순서대로 출력하세요.
                        """,
                        null
                ),
                new OllamaChatMessage(
                        "user",
                        userPrompt,
                        null
                )
        );
    }

    private List<Integer> parseRankedIndexes(
            String response,
            int candidateCount
    ) {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }

        Matcher matcher =
                NUMBER_PATTERN.matcher(
                        response
                );

        Set<Integer> rankedIndexes =
                new LinkedHashSet<>();

        while (matcher.find()) {
            int candidateNumber =
                    Integer.parseInt(
                            matcher.group()
                    );

            int candidateIndex =
                    candidateNumber - 1;

            if (
                    candidateIndex >= 0
                            && candidateIndex < candidateCount
            ) {
                rankedIndexes.add(
                        candidateIndex
                );
            }
        }

        return new ArrayList<>(
                rankedIndexes
        );
    }

    private List<RetrievedChunk> buildRerankedChunks(
            List<RetrievedChunk> candidates,
            List<Integer> rankedIndexes,
            int topK
    ) {
        List<RetrievedChunk> result =
                new ArrayList<>();

        Set<Integer> selectedIndexes =
                new LinkedHashSet<>();

        for (Integer rankedIndex : rankedIndexes) {
            if (result.size() >= topK) {
                break;
            }

            result.add(
                    candidates.get(
                            rankedIndex
                    )
            );

            selectedIndexes.add(
                    rankedIndex
            );
        }

        for (
                int index = 0;
                index < candidates.size();
                index++
        ) {
            if (result.size() >= topK) {
                break;
            }

            if (selectedIndexes.contains(index)) {
                continue;
            }

            result.add(
                    candidates.get(index)
            );
        }

        return result;
    }

    private List<RetrievedChunk> limitCandidates(
            List<RetrievedChunk> candidates,
            int topK
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .limit(topK)
                .toList();
    }

    private String limitContent(
            String content
    ) {
        if (!StringUtils.hasText(content)) {
            return "";
        }

        if (content.length() <= MAX_CHUNK_CONTENT_LENGTH) {
            return content;
        }

        return content.substring(
                0,
                MAX_CHUNK_CONTENT_LENGTH
        ) + "\n...(후보 내용 일부 생략)...";
    }
}