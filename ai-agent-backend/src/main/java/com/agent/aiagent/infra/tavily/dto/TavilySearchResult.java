package com.agent.aiagent.infra.tavily.dto;

public record TavilySearchResult(
        String title,
        String url,
        String content,
        double score
) {
}