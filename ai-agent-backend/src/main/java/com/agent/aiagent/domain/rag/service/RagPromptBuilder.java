package com.agent.aiagent.domain.rag.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.file.service.FilePromptBuilder;
import com.agent.aiagent.provider.chat.ChatModelMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagPromptBuilder {

    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of(
                    "mp4"
            );

    private final FilePromptBuilder filePromptBuilder;
    private final RagQueryRewriteService ragQueryRewriteService;
    private final RagMultiQueryService ragMultiQueryService;
    private final ChatFileRepository chatFileRepository;

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

        List<ChatFile> chatFiles =
                chatFileRepository.findAllById(
                        documentFileIds
                );

        if (
                isVideoSummaryQuestion(
                        userContent,
                        chatFiles
                )
        ) {
            String videoSummaryPrompt =
                    buildVideoSummaryPrompt(
                            roomId,
                            chatFiles,
                            userContent
                    );

            if (StringUtils.hasText(videoSummaryPrompt)) {
                log.info(
                        "영상 전체 요약 프롬프트 생성 완료. roomId={}, fileCount={}",
                        roomId,
                        chatFiles.size()
                );

                return videoSummaryPrompt;
            }
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

    private boolean isVideoSummaryQuestion(
            String userContent,
            List<ChatFile> chatFiles
    ) {
        if (!StringUtils.hasText(userContent)) {
            return false;
        }

        boolean hasVideo =
                chatFiles.stream()
                        .anyMatch(this::isVideo);

        if (!hasVideo) {
            return false;
        }

        String question =
                userContent
                        .replace(" ", "")
                        .toLowerCase();

        return question.contains("전체요약")
                || question.contains("영상요약")
                || question.contains("전체내용")
                || question.contains("영상내용")
                || question.contains("줄거리")
                || question.contains("요약해줘")
                || question.contains("요약해줘")
                || question.contains("무슨이야기")
                || question.contains("무슨내용")
                || question.contains("어떤내용");
    }

    private String buildVideoSummaryPrompt(
            String roomId,
            List<ChatFile> chatFiles,
            String userContent
    ) {
        StringBuilder summaryContent =
                new StringBuilder();

        int videoCount =
                0;

        for (ChatFile chatFile : chatFiles) {
            if (!isVideo(chatFile)) {
                continue;
            }

            if (!roomId.equals(chatFile.getRoomId())) {
                continue;
            }

            if (!StringUtils.hasText(chatFile.getSummary())) {
                continue;
            }

            videoCount++;

            summaryContent.append("[영상 ")
                    .append(videoCount)
                    .append("]\n");

            summaryContent.append("파일명: ")
                    .append(chatFile.getOriginalName())
                    .append("\n");

            summaryContent.append("전체 요약:\n")
                    .append(chatFile.getSummary())
                    .append("\n\n");
        }

        if (videoCount == 0) {
            return null;
        }

        return """
                다음은 사용자가 업로드한 영상의 전체 분석 요약입니다.

                아래 내용은 영상 전체 자막을 분석하여 미리 생성한 요약입니다.
                사용자의 질문이 영상 전체 내용이나 줄거리, 전체 요약에 관한 경우
                검색된 일부 장면이 아니라 아래 전체 요약을 우선 근거로 답변하세요.

                영상 전체 요약:
                --------------------
                %s
                --------------------

                답변 규칙:
                1. 제공된 전체 요약에 있는 내용만 사실로 답변하세요.
                2. 제공된 내용에 없는 사건이나 인물 관계를 추측하지 마세요.
                3. 사용자가 전체 내용을 요청했다면 이야기 흐름이 이해되도록 자연스럽게 정리하세요.
                4. 한국어로 답변하세요.

                사용자 질문:
                %s
                """.formatted(
                summaryContent,
                userContent
        );
    }

    private boolean isVideo(
            ChatFile chatFile
    ) {
        if (
                chatFile == null
                        || !StringUtils.hasText(
                        chatFile.getExtension()
                )
        ) {
            return false;
        }

        return VIDEO_EXTENSIONS.contains(
                chatFile.getExtension()
                        .toLowerCase()
        );
    }
}