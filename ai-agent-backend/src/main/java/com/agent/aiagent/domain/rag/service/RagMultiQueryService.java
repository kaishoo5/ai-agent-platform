package com.agent.aiagent.domain.rag.service;

import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagMultiQueryService {

    private static final int MAX_QUERY_COUNT = 3;

    private final ChatModelProvider chatModelProvider;

    public List<String> generate(
            String searchQuestion
    ) {
        if (!StringUtils.hasText(searchQuestion)) {
            return List.of();
        }

        List<ChatModelMessage> messages =
                createMultiQueryMessages(
                        searchQuestion
                );

        try {
            String response =
                    chatModelProvider.chatOnce(
                            ChatModelType.TEXT,
                            messages
                    );

            List<String> generatedQueries =
                    parseQueries(
                            searchQuestion,
                            response
                    );

            log.info(
                    "RAG Multi Query 생성 완료. originalQuestion={}, generatedQueries={}",
                    searchQuestion,
                    generatedQueries
            );

            return generatedQueries;
        } catch (Exception exception) {
            log.warn(
                    "RAG Multi Query 생성에 실패하여 단일 검색 질문을 사용합니다. question={}",
                    searchQuestion,
                    exception
            );

            return List.of(
                    searchQuestion
            );
        }
    }

    private List<ChatModelMessage> createMultiQueryMessages(
            String searchQuestion
    ) {
        String userPrompt =
                """
                다음 검색 질문을 문서 검색에 적합한 최대 3개의 검색 질문으로 분해하세요.

                원본 검색 질문:
                %s

                규칙:
                1. 원본 질문이 여러 항목을 요구하면 항목별 독립 질문으로 분해하세요.
                2. 원본 질문이 단순하면 의미가 다른 표현으로 검색 질문을 생성하세요.
                3. 각 질문은 문서 검색만으로 이해 가능한 독립 질문이어야 합니다.
                4. 질문에 답변하지 마세요.
                5. 번호, 설명, 따옴표, 코드 블록을 출력하지 마세요.
                6. 검색 질문을 한 줄에 하나씩 출력하세요.
                7. 최대 3개만 출력하세요.

                출력 예시:
                최민호가 담당하는 업무는 무엇인가?
                검색 품질 회의에서 결정된 내용은 무엇인가?
                앞으로 예정된 개발 계획은 무엇인가?
                """.formatted(
                        searchQuestion
                );

        return List.of(
                new ChatModelMessage(
                        "system",
                        """
                        당신은 RAG 문서 검색용 Multi Query 생성기입니다.
                        하나의 사용자 질문을 여러 검색 관점으로 분해하여
                        관련 문서 청크의 검색 누락을 줄이는 역할을 합니다.
                        """,
                        null
                ),
                new ChatModelMessage(
                        "user",
                        userPrompt,
                        null
                )
        );
    }

    private List<String> parseQueries(
            String searchQuestion,
            String response
    ) {
        Set<String> queries =
                new LinkedHashSet<>();

        /*
         * 원본 Rewrite 질문도 반드시 포함한다.
         * Multi Query 생성 결과가 일부 정보를 빠뜨리더라도
         * 기존 검색 품질을 유지하기 위한 안전장치다.
         */
        queries.add(
                normalizeQuery(
                        searchQuestion
                )
        );

        if (StringUtils.hasText(response)) {
            response.lines()
                    .map(this::normalizeQuery)
                    .filter(StringUtils::hasText)
                    .forEach(queries::add);
        }

        return new ArrayList<>(
                queries
        ).stream()
                .limit(MAX_QUERY_COUNT)
                .toList();
    }

    private String normalizeQuery(
            String query
    ) {
        if (!StringUtils.hasText(query)) {
            return "";
        }

        return query
                .replaceAll(
                        "^\\s*[-*•]\\s*",
                        ""
                )
                .replaceAll(
                        "^\\s*\\d+[.)]\\s*",
                        ""
                )
                .replaceAll(
                        "^(검색 질문|질문)\\s*:\\s*",
                        ""
                )
                .replaceAll(
                        "^['\"]|['\"]$",
                        ""
                )
                .trim();
    }
}