package com.agent.aiagent.infra.tavily;

import com.agent.aiagent.infra.tavily.dto.TavilySearchRequest;
import com.agent.aiagent.infra.tavily.dto.TavilySearchResponse;
import com.agent.aiagent.infra.tavily.dto.TavilySearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class TavilyClient {

    private final WebClient tavilyWebClient;

    public TavilyClient(
            @Qualifier("tavilyWebClient")
            WebClient tavilyWebClient
    ) {
        this.tavilyWebClient =
                tavilyWebClient;
    }

    public TavilySearchResponse search(
            String query,
            String searchDepth,
            int maxResults
    ) {
        TavilySearchRequest request =
                new TavilySearchRequest(
                        query,
                        searchDepth,
                        maxResults
                );

        TavilySearchResponse response =
                tavilyWebClient.post()
                        .uri("/search")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .accept(
                                MediaType.APPLICATION_JSON
                        )
                        .bodyValue(
                                request
                        )
                        .retrieve()
                        .onStatus(
                                status -> status.isError(),
                                clientResponse ->
                                        clientResponse.bodyToMono(
                                                        String.class
                                                )
                                                .defaultIfEmpty("")
                                                .flatMap(errorBody -> {
                                                    log.error(
                                                            "Tavily 검색 호출 실패. status={}, query={}, body={}",
                                                            clientResponse.statusCode(),
                                                            query,
                                                            errorBody
                                                    );

                                                    return clientResponse.createException();
                                                })
                        )
                        .bodyToMono(
                                TavilySearchResponse.class
                        )
                        .block();

        if (
                response == null
                        || response.results() == null
        ) {
            throw new IllegalStateException(
                    "Tavily 검색 응답이 비어 있습니다."
            );
        }

        for (int i = 0; i < response.results().size(); i++) {
            TavilySearchResult result =
                    response.results().get(i);

            log.info(
                    "Tavily 검색 결과. index={}, score={}, title={}, url={}, content={}",
                    i + 1,
                    result.score(),
                    result.title(),
                    result.url(),
                    result.content()
            );
        }

        log.info(
                "Tavily 검색 완료. query={}, resultCount={}, responseTime={}",
                query,
                response.results().size(),
                response.responseTime()
        );

        return response;
    }
}