package com.agent.aiagent.domain.rag.service;

import com.agent.aiagent.provider.chat.ChatModelMessage;
import com.agent.aiagent.provider.chat.ChatModelProvider;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryRewriteService {

    private static final int MAX_HISTORY_COUNT = 6;

    private final ChatModelProvider chatModelProvider;

    public String rewrite(
            List<ChatModelMessage> messages,
            String currentQuestion
    ) {
        if (!StringUtils.hasText(currentQuestion)) {
            return currentQuestion;
        }

        if (messages == null || messages.size() <= 1) {
            return currentQuestion;
        }

        List<ChatModelMessage> rewriteMessages =
                createRewriteMessages(
                        messages,
                        currentQuestion
                );

        try {
            String rewrittenQuestion =
                    chatModelProvider.chatOnce(
                            ChatModelType.TEXT,
                            rewriteMessages
                    );

            if (!StringUtils.hasText(rewrittenQuestion)) {
                return currentQuestion;
            }

            String normalizedQuestion =
                    normalizeRewrittenQuestion(
                            rewrittenQuestion
                    );

            log.info(
                    "RAG 검색 질문 재작성 완료. originalQuestion={}, rewrittenQuestion={}",
                    currentQuestion,
                    normalizedQuestion
            );

            return normalizedQuestion;
        } catch (Exception exception) {
            log.warn(
                    "RAG 검색 질문 재작성에 실패하여 원본 질문을 사용합니다. question={}",
                    currentQuestion,
                    exception
            );

            return currentQuestion;
        }
    }

    private List<ChatModelMessage> createRewriteMessages(
            List<ChatModelMessage> messages,
            String currentQuestion
    ) {
        List<ChatModelMessage> rewriteMessages =
                new ArrayList<>();

        rewriteMessages.add(
                new ChatModelMessage(
                        "system",
                        """
                        당신은 RAG 문서 검색용 질문 재작성기입니다.

                        사용자의 최신 질문이 이전 대화에 의존한다면,
                        이전 대화의 문맥을 반영하여 독립적으로 이해 가능한 질문으로 바꾸세요.

                        규칙:
                        1. 답변하지 말고 검색용 질문만 출력하세요.
                        2. "그 사람", "그것", "그 프로젝트", "해당 일정" 같은 표현은 구체적인 대상으로 바꾸세요.
                        3. 이전 대화와 무관한 질문은 원문을 그대로 유지하세요.
                        4. 설명, 따옴표, 접두어를 붙이지 마세요.
                        5. 한 문장으로 출력하세요.
                        """,
                        null
                )
        );

        int startIndex =
                Math.max(
                        0,
                        messages.size() - MAX_HISTORY_COUNT
                );

        for (
                int index = startIndex;
                index < messages.size();
                index++
        ) {
            ChatModelMessage message =
                    messages.get(index);

            if (!StringUtils.hasText(message.getContent())) {
                continue;
            }

            rewriteMessages.add(
                    new ChatModelMessage(
                            message.getRole(),
                            message.getContent(),
                            null
                    )
            );
        }

        rewriteMessages.add(
                new ChatModelMessage(
                        "user",
                        """
                        최신 사용자 질문을 문서 검색용 독립 질문으로 재작성하세요.

                        최신 질문:
                        %s
                        """.formatted(currentQuestion),
                        null
                )
        );

        return rewriteMessages;
    }

    private String normalizeRewrittenQuestion(
            String rewrittenQuestion
    ) {
        return rewrittenQuestion
                .replaceAll(
                        "^(재작성된 질문|검색 질문|질문)\\s*:\\s*",
                        ""
                )
                .replaceAll(
                        "^['\"]|['\"]$",
                        ""
                )
                .trim();
    }
}