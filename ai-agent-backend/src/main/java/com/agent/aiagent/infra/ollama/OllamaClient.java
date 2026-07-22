package com.agent.aiagent.infra.ollama;

import com.agent.aiagent.domain.chat.dto.OllamaGenerateRequest;
import com.agent.aiagent.domain.chat.dto.OllamaGenerateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final WebClient ollamaWebClient;

    public Flux<OllamaGenerateResponse> generate(String prompt) {
        OllamaGenerateRequest request = OllamaGenerateRequest.builder()
                .model("qwen3:4b")
                .prompt(prompt)
                .stream(true)
                .build();

        return ollamaWebClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(OllamaGenerateResponse.class);
    }

}