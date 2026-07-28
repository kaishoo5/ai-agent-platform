package com.agent.aiagent.infra.tavily.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TavilySearchResponse(
        String query,
        List<TavilySearchResult> results,

        @JsonProperty("response_time")
        double responseTime,

        @JsonProperty("request_id")
        String requestId
) {
}