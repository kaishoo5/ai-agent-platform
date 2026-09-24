package com.agent.aiagent.domain.memory.service;

import com.agent.aiagent.domain.memory.entity.AgentMemory;
import com.agent.aiagent.domain.memory.repository.AgentMemoryRepository;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import com.agent.aiagent.provider.embedding.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private static final String SYSTEM_ROLE = "system";
    private static final int MEMORY_CANDIDATE_LIMIT = 20;
    private static final double MIN_MEMORY_SCORE = 0.35;

    private final AgentMemoryRepository agentMemoryRepository;
    private final AgentMemorySettingsService agentMemorySettingsService;
    private final EmbeddingProvider embeddingProvider;
    private final AgentMemoryEmbeddingConverter embeddingConverter;
    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public List<AgentMemory> findRelevantMemories(
            String question,
            int topK
    ) {
        if (
                !agentMemorySettingsService.isEnabled()
                        || !StringUtils.hasText(question)
                        || topK <= 0
        ) {
            return List.of();
        }

        List<AgentMemory> memories =
                agentMemoryRepository.findAll();

        if (memories.isEmpty()) {
            return List.of();
        }

        List<Double> questionEmbedding =
                createEmbedding(
                        question
                );

        List<ScoredMemory> scoredMemories =
                memories.stream()
                        .map(memory ->
                                new ScoredMemory(
                                        memory,
                                        cosineSimilarity(
                                                questionEmbedding,
                                                embeddingConverter.deserialize(
                                                        memory.getEmbedding()
                                                )
                                        )
                                )
                        )
                        .sorted(
                                Comparator.comparingDouble(
                                                ScoredMemory::score
                                        )
                                        .reversed()
                        )
                        .limit(
                                MEMORY_CANDIDATE_LIMIT
                        )
                        .toList();

        List<AgentMemory> selectedMemories =
                scoredMemories.stream()
                        .filter(scoredMemory ->
                                scoredMemory.score()
                                        >= MIN_MEMORY_SCORE
                        )
                        .limit(topK)
                        .map(
                                ScoredMemory::memory
                        )
                        .toList();

        log.info(
                "Agent Memory 검색 완료. memoryCount={}, selectedCount={}, topK={}",
                memories.size(),
                selectedMemories.size(),
                topK
        );

        return selectedMemories;
    }

    public String createMemoryPrompt(
            List<AgentMemory> memories
    ) {
        if (
                memories == null
                        || memories.isEmpty()
        ) {
            return null;
        }

        StringBuilder builder =
                new StringBuilder(
                        """
                        다음 내용은 이전 채팅방들에서 저장된 장기 메모리입니다.

                        [장기 메모리]
                        """
                );

        for (AgentMemory memory : memories) {
            builder.append("- [")
                    .append(
                            memory.getCategory()
                    )
                    .append("] ")
                    .append(
                            memory.getContent()
                    )
                    .append(System.lineSeparator());
        }

        builder.append(
                """

                현재 질문과 관련된 경우에만 참고하세요.
                메모리보다 현재 사용자의 명시적인 말이 우선합니다.
                메모리에 없는 사실은 임의로 만들지 마세요.
                """
        );

        return builder.toString()
                .trim();
    }

    public void refreshFromConversation(
            String roomId,
            String userContent,
            String assistantContent
    ) {
        if (
                !agentMemorySettingsService.isEnabled()
                        || !StringUtils.hasText(userContent)
        ) {
            return;
        }

        try {
            List<AgentMemory> existingMemories =
                    findRelevantMemories(
                            userContent,
                            8
                    );

            String extractionResult =
                    extractMemoryActions(
                            userContent,
                            assistantContent,
                            existingMemories
                    );

            applyMemoryActions(
                    roomId,
                    extractionResult
            );
        } catch (Exception exception) {
            log.warn(
                    "Agent Memory 갱신에 실패했습니다. 채팅 응답에는 영향을 주지 않습니다. roomId={}",
                    roomId,
                    exception
            );
        }
    }

    public List<AgentMemory> findAllMemories() {
        return agentMemoryRepository
                .findAllByOrderByUpdatedAtDesc();
    }

    public AgentMemory updateMemory(
            String memoryId,
            String category,
            String content
    ) {
        String normalizedCategory =
                normalizeCategory(
                        category
                );

        String normalizedContent =
                normalizeContent(
                        content
                );

        if (!StringUtils.hasText(normalizedContent)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "메모리 내용을 입력해주세요."
            );
        }

        AgentMemory memory =
                agentMemoryRepository
                        .findById(
                                memoryId
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "메모리를 찾을 수 없습니다."
                                )
                        );

        memory.update(
                normalizedCategory,
                normalizedContent,
                embeddingConverter.serialize(
                        createEmbedding(
                                normalizedContent
                        )
                )
        );

        return agentMemoryRepository.save(
                memory
        );
    }

    public void deleteMemory(
            String memoryId
    ) {
        if (!agentMemoryRepository.existsById(memoryId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "메모리를 찾을 수 없습니다."
            );
        }

        agentMemoryRepository.deleteById(
                memoryId
        );
    }

    public void deleteAllMemories() {
        agentMemoryRepository.deleteAll();
    }

    private String extractMemoryActions(
            String userContent,
            String assistantContent,
            List<AgentMemory> existingMemories
    ) {
        String existingMemoryText =
                buildExistingMemoryText(
                        existingMemories
                );

        String prompt =
                """
                아래 사용자 메시지에서 다른 채팅방에서도 다시 사용할 가치가 있는 장기 정보를 추출하세요.

                [관련 기존 메모리]
                %s

                [사용자 메시지]
                %s

                [AI 응답 - 문맥 확인용]
                %s

                저장 대상 예시:
                - 사용자의 지속적인 선호 또는 기피
                - 직업, 기술 스택, 개발 환경
                - 장기 프로젝트와 프로젝트의 중요한 결정
                - 반복해서 활용할 목표, 작업 방식, 제약조건

                저장하지 말아야 할 내용:
                - 일회성 질문
                - 현재 시각, 단기 상태, 단순 인사
                - AI가 추측하거나 새로 만들어낸 사실
                - 사용자 메시지에 명시되지 않은 AI 응답의 내용
                - 비밀번호, 인증키, 토큰 등 비밀 정보

                기존 메모리와 같은 주제의 최신 정보가 있으면 UPDATE 하세요.
                새로운 장기 정보면 ADD 하세요.
                저장할 정보가 없으면 빈 배열을 반환하세요.

                반드시 JSON 배열만 출력하세요. Markdown 코드 블록을 사용하지 마세요.

                형식:
                [
                  {
                    "action": "ADD",
                    "memoryId": null,
                    "category": "PROJECT",
                    "content": "사용자의 프로젝트는 Spring Boot 4와 React 19를 사용한다."
                  },
                  {
                    "action": "UPDATE",
                    "memoryId": "기존 메모리 ID",
                    "category": "PREFERENCE",
                    "content": "사용자는 Vue 3를 선호한다."
                  }
                ]
                """.formatted(
                        existingMemoryText,
                        userContent,
                        StringUtils.hasText(assistantContent)
                                ? assistantContent
                                : "없음"
                );

        return chatModelProvider.chatOnce(
                new ChatModelRequest(
                        ChatModelType.TEXT,
                        List.of(
                                new ChatModelMessage(
                                        SYSTEM_ROLE,
                                        """
                                        당신은 Agent Memory 관리자입니다.
                                        사용자가 직접 말한 장기적으로 유용한 사실만 저장 후보로 추출합니다.
                                        출력은 반드시 요청된 JSON 배열 형식만 사용합니다.
                                        """,
                                        null
                                ),
                                new ChatModelMessage(
                                        "user",
                                        prompt,
                                        null
                                )
                        ),
                        List.of()
                )
        ).content();
    }

    private void applyMemoryActions(
            String roomId,
            String extractionResult
    ) {
        if (!StringUtils.hasText(extractionResult)) {
            return;
        }

        JsonNode root =
                readJsonArray(
                        extractionResult
                );

        if (
                root == null
                        || !root.isArray()
                        || root.isEmpty()
        ) {
            return;
        }

        List<MemoryAction> actions =
                new ArrayList<>();

        for (JsonNode node : root) {
            String action =
                    textValue(
                            node,
                            "action"
                    );

            String memoryId =
                    textValue(
                            node,
                            "memoryId"
                    );

            String category =
                    normalizeCategory(
                            textValue(
                                    node,
                                    "category"
                            )
                    );

            String content =
                    normalizeContent(
                            textValue(
                                    node,
                                    "content"
                            )
                    );

            if (!StringUtils.hasText(content)) {
                continue;
            }

            if (
                    !"ADD".equals(action)
                            && !"UPDATE".equals(action)
            ) {
                continue;
            }

            actions.add(
                    new MemoryAction(
                            action,
                            memoryId,
                            category,
                            content,
                            createEmbedding(
                                    content
                            )
                    )
            );
        }

        if (actions.isEmpty()) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            for (MemoryAction action : actions) {
                String serializedEmbedding =
                        embeddingConverter.serialize(
                                action.embedding()
                        );

                if (
                        "UPDATE".equals(
                                action.action()
                        )
                                && StringUtils.hasText(
                                action.memoryId()
                        )
                ) {
                    agentMemoryRepository.findById(
                                    action.memoryId()
                            )
                            .ifPresentOrElse(
                                    memory ->
                                            memory.update(
                                                    action.category(),
                                                    action.content(),
                                                    serializedEmbedding
                                            ),
                                    () ->
                                            agentMemoryRepository.save(
                                                    new AgentMemory(
                                                            action.category(),
                                                            action.content(),
                                                            serializedEmbedding
                                                    )
                                            )
                            );

                    continue;
                }

                agentMemoryRepository.save(
                        new AgentMemory(
                                action.category(),
                                action.content(),
                                serializedEmbedding
                        )
                );
            }
        });

        log.info(
                "Agent Memory 갱신 완료. roomId={}, actionCount={}",
                roomId,
                actions.size()
        );
    }

    private JsonNode readJsonArray(
            String content
    ) {
        String normalized =
                content.trim();

        if (normalized.startsWith("```")) {
            normalized = normalized
                    .replaceFirst(
                            "^```(?:json)?\\s*",
                            ""
                    )
                    .replaceFirst(
                            "\\s*```$",
                            ""
                    )
                    .trim();
        }

        try {
            return objectMapper.readTree(
                    normalized
            );
        } catch (Exception exception) {
            log.warn(
                    "Agent Memory 추출 JSON 파싱에 실패했습니다. content={}",
                    normalized,
                    exception
            );

            return null;
        }
    }

    private String buildExistingMemoryText(
            List<AgentMemory> memories
    ) {
        if (
                memories == null
                        || memories.isEmpty()
        ) {
            return "관련 기존 메모리 없음";
        }

        StringBuilder builder =
                new StringBuilder();

        for (AgentMemory memory : memories) {
            builder.append("- id=")
                    .append(
                            memory.getId()
                    )
                    .append(", category=")
                    .append(
                            memory.getCategory()
                    )
                    .append(", content=")
                    .append(
                            memory.getContent()
                    )
                    .append(System.lineSeparator());
        }

        return builder.toString()
                .trim();
    }

    private List<Double> createEmbedding(
            String content
    ) {
        List<List<Double>> embeddings =
                embeddingProvider.embed(
                        List.of(content)
                );

        if (
                embeddings == null
                        || embeddings.isEmpty()
                        || embeddings.getFirst() == null
                        || embeddings.getFirst().isEmpty()
        ) {
            throw new IllegalStateException(
                    "Agent Memory 임베딩을 생성하지 못했습니다."
            );
        }

        return embeddings.getFirst();
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

        for (int index = 0; index < vectorA.size(); index++) {
            double valueA = vectorA.get(index);
            double valueB = vectorB.get(index);

            dotProduct += valueA * valueB;
            normA += valueA * valueA;
            normB += valueB * valueB;
        }

        if (
                normA == 0.0
                        || normB == 0.0
        ) {
            return 0.0;
        }

        return dotProduct
                / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String textValue(
            JsonNode node,
            String fieldName
    ) {
        JsonNode value =
                node.get(
                        fieldName
                );

        if (
                value == null
                        || value.isNull()
        ) {
            return null;
        }

        return value.asText();
    }

    private String normalizeCategory(
            String category
    ) {
        if (!StringUtils.hasText(category)) {
            return "GENERAL";
        }

        String normalized =
                category.trim()
                        .toUpperCase(
                                Locale.ROOT
                        );

        return normalized.length() <= 50
                ? normalized
                : normalized.substring(
                0,
                50
        );
    }

    private String normalizeContent(
            String content
    ) {
        if (!StringUtils.hasText(content)) {
            return null;
        }

        String normalized =
                content.trim();

        return normalized.length() <= 1000
                ? normalized
                : normalized.substring(
                0,
                1000
        );
    }

    private record ScoredMemory(
            AgentMemory memory,
            double score
    ) {
    }

    private record MemoryAction(
            String action,
            String memoryId,
            String category,
            String content,
            List<Double> embedding
    ) {
    }
}
