package com.agent.aiagent.domain.file.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FileChunkService {

    private static final int CHUNK_SIZE = 1_000;
    private static final int CHUNK_OVERLAP = 150;

    public List<String> split(
            String content
    ) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String normalizedContent =
                content.replaceAll(
                        "\\r\\n?",
                        "\n"
                );

        List<String> chunks =
                new ArrayList<>();

        int startIndex = 0;

        while (startIndex < normalizedContent.length()) {
            int endIndex =
                    Math.min(
                            startIndex + CHUNK_SIZE,
                            normalizedContent.length()
                    );

            String chunk =
                    normalizedContent.substring(
                            startIndex,
                            endIndex
                    ).trim();

            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (endIndex >= normalizedContent.length()) {
                break;
            }

            startIndex =
                    Math.max(
                            endIndex - CHUNK_OVERLAP,
                            startIndex + 1
                    );
        }

        return chunks;
    }
}