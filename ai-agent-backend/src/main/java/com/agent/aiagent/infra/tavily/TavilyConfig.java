package com.agent.aiagent.infra.tavily;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class TavilyConfig {

    @Bean
    public WebClient tavilyWebClient(
            TavilyProperties tavilyProperties
    ) {
        String apiKey =
                tavilyProperties.getApiKey();

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "TAVILY_API_KEY 환경 변수가 설정되지 않았습니다."
            );
        }

        return WebClient.builder()
                .baseUrl(
                        tavilyProperties.getBaseUrl()
                )
                .defaultHeaders(headers ->
                        headers.setBearerAuth(
                                apiKey.trim()
                        )
                )
                .build();
    }
}