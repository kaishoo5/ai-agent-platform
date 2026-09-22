package com.agent.aiagent.infra.ollama;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OllamaConfig {

    public static final String OLLAMA_BASE_URL =
            "http://localhost:11434";

    @Bean
    public WebClient ollamaWebClient() {
        return WebClient.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .build();
    }

}