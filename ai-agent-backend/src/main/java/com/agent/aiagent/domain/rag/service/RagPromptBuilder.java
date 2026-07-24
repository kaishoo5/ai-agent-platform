package com.agent.aiagent.domain.rag.service;

import com.agent.aiagent.domain.file.service.FilePromptBuilder;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagPromptBuilder {

    private final FilePromptBuilder filePromptBuilder;
    private final RagQueryRewriteService ragQueryRewriteService;
    private final RagMultiQueryService ragMultiQueryService;

    public String build(
            String roomId,
            List<String> documentFileIds,
            List<ChatModelMessage> messages,
            String userContent
    ) {
        if (
                documentFileIds == null
                        || documentFileIds.isEmpty()
        ) {
            return userContent;
        }

        String searchQuestion =
                ragQueryRewriteService.rewrite(
                        messages,
                        userContent
                );

        List<String> searchQuestions =
                ragMultiQueryService.generate(
                        searchQuestion
                );

        String prompt =
                filePromptBuilder.build(
                        roomId,
                        documentFileIds,
                        userContent,
                        searchQuestion,
                        searchQuestions
                );

        log.info(
                "RAG 프롬프트 생성 완료. roomId={}, documentCount={}, queryCount={}",
                roomId,
                documentFileIds.size(),
                searchQuestions.size()
        );

        return prompt;
    }
}