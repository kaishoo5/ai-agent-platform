package com.agent.aiagent.infra.tavily.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TavilySearchRequest(

        String query,

        @JsonProperty("search_depth")
        String searchDepth,

        @JsonProperty("max_results")
        int maxResults
) {
}