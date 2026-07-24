package com.agent.aiagent.domain.file.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbeddingJsonConverter {

    private final ObjectMapper objectMapper;

    public String serialize(
            List<Double> embedding
    ) {
        try {
            return objectMapper.writeValueAsString(
                    embedding
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "임베딩 직렬화에 실패했습니다.",
                    exception
            );
        }
    }

    public List<Double> deserialize(
            String embedding
    ) {
        try {
            return objectMapper.readValue(
                    embedding,
                    new TypeReference<List<Double>>() {
                    }
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "임베딩 역직렬화에 실패했습니다.",
                    exception
            );
        }
    }
}