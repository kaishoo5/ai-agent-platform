package com.agent.aiagent.infra.tavily;

import com.agent.aiagent.infra.tavily.dto.TavilySearchRequest;
import com.agent.aiagent.infra.tavily.dto.TavilySearchResponse;
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
            int maxResults
    ) {
        TavilySearchRequest request =
                new TavilySearchRequest(
                        query,
                        "basic",
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

        log.info(
                "Tavily 검색 완료. query={}, resultCount={}, responseTime={}",
                query,
                response.results().size(),
                response.responseTime()
        );

        return response;
    }
}