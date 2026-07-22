package com.agent.aiagent.infra.ollama;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OllamaConfig {

    @Bean
    public WebClient ollamaWebClient() {
        return WebClient.builder()
                .baseUrl("http://localhost:11434")
                .build();
    }

}