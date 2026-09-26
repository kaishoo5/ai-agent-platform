package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.infra.tavily.dto.TavilySearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class WebSearchResultFilter {

    public List<TavilySearchResult> filter(
            String query,
            List<TavilySearchResult> results
    ) {
        if (
                results == null
                        || results.isEmpty()
        ) {
            return List.of();
        }

        List<TavilySearchResult> filteredResults =
                new ArrayList<>();

        Set<String> urls =
                new HashSet<>();

        Set<String> titles =
                new HashSet<>();

        for (TavilySearchResult result : results) {
            if (!isValid(result)) {
                continue;
            }

            String normalizedUrl =
                    normalize(
                            result.url()
                    );

            String normalizedTitle =
                    normalize(
                            result.title()
                    );

            if (
                    urls.contains(normalizedUrl)
                            || titles.contains(normalizedTitle)
            ) {
                log.debug(
                        "Web Search 중복 결과 제외. query={}, title={}, url={}",
                        query,
                        result.title(),
                        result.url()
                );

                continue;
            }

            urls.add(
                    normalizedUrl
            );

            titles.add(
                    normalizedTitle
            );

            filteredResults.add(
                    result
            );
        }

        log.info(
                "Web Search 결과 정제 완료. query={}, originalCount={}, filteredCount={}",
                query,
                results.size(),
                filteredResults.size()
        );

        return filteredResults;
    }

    private boolean isValid(
            TavilySearchResult result
    ) {
        if (result == null) {
            return false;
        }

        if (isBlank(result.title())) {
            return false;
        }

        if (isBlank(result.url())) {
            return false;
        }

        return !isBlank(
                result.content()
        );
    }

    private boolean isBlank(
            String value
    ) {
        return value == null
                || value.isBlank();
    }

    private String normalize(
            String value
    ) {
        return value.trim()
                .toLowerCase();
    }
}