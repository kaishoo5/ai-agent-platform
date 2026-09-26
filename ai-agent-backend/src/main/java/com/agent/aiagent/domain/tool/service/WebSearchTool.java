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

    private static final String DEFAULT_SEARCH_DEPTH =
            "basic";

    private static final String ADVANCED_SEARCH_DEPTH =
            "advanced";

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "web_search",
                    """
                    최신 뉴스, 현재 정보, 웹 문서 등 인터넷 검색이 필요한 질문을 검색합니다.

                    일반적인 최신 정보 확인이나 간단한 사실 검색에는
                    searchDepth를 basic으로 사용합니다.

                    사용자가 '자세히', '상세히', '심층적으로', '깊게',
                    '철저히', '조사해서', '분석해서' 등 상세하거나 깊은
                    검색을 명시적으로 요청한 경우에는
                    searchDepth를 반드시 advanced로 사용합니다.

                    상세하거나 심층적인 검색을 요청한 경우에는
                    충분한 검색 결과를 확보할 수 있도록
                    maxResults를 10으로 사용하는 것을 우선합니다.

                    이미 얻은 검색 결과만으로 충분히 답변할 수 있다면
                    동일하거나 유사한 검색을 불필요하게 반복하지 마세요.
                    """,
                    Map.of(
                            "query",
                            new ToolParameter(
                                    "string",
                                    """
                                    인터넷에서 검색할 검색어입니다.

                                    사용자의 질문 의도를 유지하면서
                                    검색에 적합한 간결한 검색어를 작성합니다.

                                    최신 정보가 필요한 경우 현재 시점을
                                    고려하여 검색어를 작성합니다.
                                    """,
                                    true
                            ),
                            "maxResults",
                            new ToolParameter(
                                    "integer",
                                    """
                                    반환할 최대 검색 결과 수입니다.

                                    일반적인 검색은 5를 사용합니다.

                                    사용자가 '자세히', '상세히', '심층적으로',
                                    '깊게', '철저히', '조사해서', '분석해서'
                                    등 상세하거나 깊은 검색을 요청한 경우에는
                                    10을 사용하는 것을 우선합니다.

                                    최소값은 1이고 최대값은 10입니다.
                                    기본값은 5입니다.
                                    """,
                                    false
                            ),
                            "searchDepth",
                            new ToolParameter(
                                    "string",
                                    """
                                    검색 깊이입니다.
                                    허용값은 basic 또는 advanced입니다.

                                    basic:
                                    일반적인 웹 검색, 간단한 최신 정보 확인,
                                    빠른 사실 확인에 사용합니다.

                                    advanced:
                                    더 깊고 상세한 검색이 필요한 경우 사용합니다.

                                    사용자가 '자세히', '상세히', '심층적으로',
                                    '깊게', '철저히', '조사해서', '분석해서'
                                    등의 표현으로 상세 검색을 명시적으로
                                    요청했다면 반드시 advanced를 사용합니다.

                                    사용자가 상세 검색을 요청하지 않은
                                    일반적인 검색에서는 basic을 사용합니다.

                                    기본값은 basic입니다.
                                    """,
                                    false
                            )
                    )
            );

    private final TavilyClient tavilyClient;
    private final WebSearchResultFilter webSearchResultFilter;

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

        String searchDepth =
                getSearchDepth(
                        arguments
                );

        try {
            TavilySearchResponse response =
                    tavilyClient.search(
                            query,
                            searchDepth,
                            maxResults
                    );

            List<TavilySearchResult> results =
                    webSearchResultFilter.filter(
                            query,
                            response.results()
                    );

            if (results.isEmpty()) {
                return ToolResult.failure(
                        "검색 결과를 찾지 못했습니다."
                );
            }

            String content =
                    buildResultContent(
                            query,
                            searchDepth,
                            results
                    );

            log.info(
                    "Web Search Tool 실행 완료. query={}, searchDepth={}, resultCount={}",
                    query,
                    searchDepth,
                    results.size()
            );

            return ToolResult.success(
                    content
            );
        } catch (Exception exception) {
            log.error(
                    "Web Search Tool 실행 실패. query={}, searchDepth={}",
                    query,
                    searchDepth,
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

    private String getSearchDepth(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return DEFAULT_SEARCH_DEPTH;
        }

        Object searchDepth =
                arguments.get(
                        "searchDepth"
                );

        if (searchDepth == null) {
            return DEFAULT_SEARCH_DEPTH;
        }

        String value =
                searchDepth.toString()
                        .trim()
                        .toLowerCase();

        if (
                ADVANCED_SEARCH_DEPTH.equals(
                        value
                )
        ) {
            return ADVANCED_SEARCH_DEPTH;
        }

        return DEFAULT_SEARCH_DEPTH;
    }

    private String buildResultContent(
            String query,
            String searchDepth,
            List<TavilySearchResult> results
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append("WEB_SEARCH_RESULT\n");
        content.append("QUERY: ")
                .append(query)
                .append("\n");

        content.append("SEARCH_DEPTH: ")
                .append(searchDepth)
                .append("\n");

        content.append("SOURCE_COUNT: ")
                .append(results.size())
                .append("\n\n");

        content.append("""
            아래 SOURCE들은 서로 독립된 검색 결과입니다.
            각 SOURCE의 TITLE, URL, CONTENT는 반드시 같은 출처의 정보로 취급하세요.
            서로 다른 SOURCE의 내용을 하나의 출처가 제공한 것처럼 합치지 마세요.
            최종 답변에서 사실, 숫자, 날짜, 금액, 국가, 제품명 등을 사용할 때는
            해당 SOURCE의 CONTENT에서 확인할 수 있는 정보만 사용하세요.

            """);

        IntStream.range(
                        0,
                        results.size()
                )
                .forEach(index -> {
                    TavilySearchResult result =
                            results.get(index);

                    int sourceNumber =
                            index + 1;

                    content.append("===== SOURCE_")
                            .append(sourceNumber)
                            .append(" =====\n");

                    content.append("TITLE: ")
                            .append(result.title())
                            .append("\n");

                    content.append("URL: ")
                            .append(result.url())
                            .append("\n");

                    content.append("CONTENT:\n")
                            .append(result.content())
                            .append("\n");

                    content.append("RELEVANCE_SCORE: ")
                            .append(result.score())
                            .append("\n");

                    content.append("===== END_SOURCE_")
                            .append(sourceNumber)
                            .append(" =====\n\n");
                });

        return content.toString()
                .trim();
    }
}