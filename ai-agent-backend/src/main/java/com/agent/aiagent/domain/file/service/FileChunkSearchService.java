package com.agent.aiagent.domain.file.service;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FileChunkSearchService {

    private static final int MAX_RESULT_COUNT = 5;
    private static final int MIN_SCORE = 2;

    public List<String> search(
            String question,
            List<String> chunks
    ) {
        if (
                question == null
                        || question.isBlank()
                        || chunks == null
                        || chunks.isEmpty()
        ) {
            return List.of();
        }

        Set<String> questionTerms =
                extractTerms(
                        question
                );

        if (questionTerms.isEmpty()) {
            return List.of();
        }

        return chunks.stream()
                .map(chunk ->
                        new ScoredChunk(
                                chunk,
                                calculateScore(
                                        questionTerms,
                                        chunk
                                )
                        )
                )
                .filter(scoredChunk ->
                        scoredChunk.score() >= MIN_SCORE
                )
                .sorted(
                        Comparator.comparingInt(
                                ScoredChunk::score
                        ).reversed()
                )
                .limit(MAX_RESULT_COUNT)
                .map(ScoredChunk::content)
                .toList();
    }

    private int calculateScore(
            Set<String> questionTerms,
            String chunk
    ) {
        Set<String> chunkTerms =
                extractTerms(
                        chunk
                );

        int score = 0;

        for (String questionTerm : questionTerms) {
            if (chunkTerms.contains(questionTerm)) {
                score++;
            }
        }

        return score;
    }

    private Set<String> extractTerms(
            String text
    ) {
        String normalizedText =
                normalize(
                        text
                );

        Set<String> terms =
                new HashSet<>();

        String[] words =
                normalizedText.split(
                        "\\s+"
                );

        for (String word : words) {
            if (word.length() < 2) {
                continue;
            }

            terms.add(word);

            addNGrams(
                    terms,
                    word,
                    2
            );

            addNGrams(
                    terms,
                    word,
                    3
            );
        }

        return terms;
    }

    private void addNGrams(
            Set<String> terms,
            String word,
            int size
    ) {
        if (word.length() < size) {
            return;
        }

        for (
                int index = 0;
                index <= word.length() - size;
                index++
        ) {
            terms.add(
                    word.substring(
                            index,
                            index + size
                    )
            );
        }
    }

    private String normalize(
            String text
    ) {
        return text.toLowerCase(
                        Locale.ROOT
                )
                .replaceAll(
                        "[^가-힣a-z0-9]+",
                        " "
                )
                .trim();
    }

    private record ScoredChunk(
            String content,
            int score
    ) {
    }
}