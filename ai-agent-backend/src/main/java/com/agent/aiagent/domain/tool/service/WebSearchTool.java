package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.infra.tavily.TavilyClient;
import com.agent.aiagent.infra.tavily.dto.TavilySearchResponse;
import com.agent.aiagent.infra.tavily.dto.TavilySearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool implements AgentTool {

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 10;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "web_search",
                    "최신 뉴스, 현재 정보, 웹 문서 등 인터넷 검색이 필요한 질문을 검색합니다.",
                    Map.of(
                            "query",
                            new ToolParameter(
                                    "string",
                                    "인터넷에서 검색할 검색어입니다.",
                                    true
                            ),
                            "maxResults",
                            new ToolParameter(
                                    "integer",
                                    "반환할 최대 검색 결과 수입니다. 기본값은 5이고 최대 10입니다.",
                                    false
                            )
                    )
            );

    private final TavilyClient tavilyClient;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String query =
                getQuery(
                        arguments
                );

        if (query == null) {
            return ToolResult.failure(
                    "검색어가 없습니다."
            );
        }

        int maxResults =
                getMaxResults(
                        arguments
                );

        try {
            TavilySearchResponse response =
                    tavilyClient.search(
                            query,
                            maxResults
                    );

            List<TavilySearchResult> results =
                    response.results();

            if (results.isEmpty()) {
                return ToolResult.failure(
                        "검색 결과를 찾지 못했습니다."
                );
            }

            String content =
                    buildResultContent(
                            query,
                            results
                    );

            log.info(
                    "Web Search Tool 실행 완료. query={}, resultCount={}",
                    query,
                    results.size()
            );

            return ToolResult.success(
                    content
            );
        } catch (Exception exception) {
            log.error(
                    "Web Search Tool 실행 실패. query={}",
                    query,
                    exception
            );

            return ToolResult.failure(
                    "웹 검색 중 오류가 발생했습니다."
            );
        }
    }

    private String getQuery(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return null;
        }

        Object query =
                arguments.get(
                        "query"
                );

        if (query == null) {
            return null;
        }

        String value =
                query.toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private int getMaxResults(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return DEFAULT_MAX_RESULTS;
        }

        Object maxResults =
                arguments.get(
                        "maxResults"
                );

        if (maxResults == null) {
            return DEFAULT_MAX_RESULTS;
        }

        try {
            int value =
                    Integer.parseInt(
                            maxResults.toString()
                    );

            if (value < 1) {
                return DEFAULT_MAX_RESULTS;
            }

            return Math.min(
                    value,
                    MAX_RESULTS_LIMIT
            );
        } catch (NumberFormatException exception) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    private String buildResultContent(
            String query,
            List<TavilySearchResult> results
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("웹 검색어: ")
                .append(query)
                .append("\n\n");

        IntStream.range(
                        0,
                        results.size()
                )
                .forEach(index -> {
                    TavilySearchResult result =
                            results.get(index);

                    content.append("[검색 결과 ")
                            .append(index + 1)
                            .append("]\n");

                    content.append("제목: ")
                            .append(result.title())
                            .append("\n");

                    content.append("URL: ")
                            .append(result.url())
                            .append("\n");

                    content.append("내용: ")
                            .append(result.content())
                            .append("\n\n");
                });

        return content.toString()
                .trim();
    }
}