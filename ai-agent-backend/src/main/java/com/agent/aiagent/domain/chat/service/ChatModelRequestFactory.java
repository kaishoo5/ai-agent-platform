package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.file.service.ChatFileService;
import com.agent.aiagent.domain.file.service.FilePromptBuilder;
import com.agent.aiagent.domain.rag.service.RagMultiQueryService;
import com.agent.aiagent.domain.rag.service.RagQueryRewriteService;
import com.agent.aiagent.infra.ollama.dto.OllamaChatMessage;
import com.agent.aiagent.provider.chat.ChatModelRequest;
import com.agent.aiagent.provider.chat.ChatModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelRequestFactory {

    private static final String USER_ROLE = "user";

    private final FilePromptBuilder filePromptBuilder;
    private final RagQueryRewriteService ragQueryRewriteService;
    private final RagMultiQueryService ragMultiQueryService;
    private final ChatFileService chatFileService;
    private final ChatFileRepository chatFileRepository;
    private final ConversationSummaryService conversationSummaryService;
    private final ChatImageEncoder chatImageEncoder;

    public ChatModelRequest create(
            ChatRequest request
    ) {
        List<ChatFile> chatFiles =
                findRequestFiles(
                        request
                );

        List<ChatFile> imageFiles =
                chatFiles.stream()
                        .filter(chatImageEncoder::isImage)
                        .toList();

        List<String> documentFileIds =
                chatFiles.stream()
                        .filter(file ->
                                !chatImageEncoder.isImage(file)
                        )
                        .map(ChatFile::getId)
                        .toList();

        List<String> encodedImages =
                imageFiles.stream()
                        .map(chatImageEncoder::encode)
                        .toList();

        List<OllamaChatMessage> messages =
                createMessages(
                        request.getRoomId(),
                        request.isRegenerate(),
                        documentFileIds,
                        encodedImages
                );

        ChatModelType modelType =
                encodedImages.isEmpty()
                        ? ChatModelType.TEXT
                        : ChatModelType.VISION;

        log.info(
                "AI 요청 생성 완료. roomId={}, modelType={}, documentCount={}, imageCount={}",
                request.getRoomId(),
                modelType,
                documentFileIds.size(),
                encodedImages.size()
        );

        return new ChatModelRequest(
                modelType,
                messages
        );
    }

    private List<ChatFile> findRequestFiles(
            ChatRequest request
    ) {
        if (
                request.getFileIds() == null
                        || request.getFileIds().isEmpty()
        ) {
            return chatFileRepository.findAllByRoomIdOrderByCreatedAtAsc(
                    request.getRoomId()
            );
        }

        return chatFileService.findFiles(
                request.getRoomId(),
                request.getFileIds()
        );
    }

    private List<OllamaChatMessage> createMessages(
            String roomId,
            boolean regenerate,
            List<String> documentFileIds,
            List<String> encodedImages
    ) {
        List<OllamaChatMessage> messages =
                conversationSummaryService.createConversationContext(
                        roomId,
                        regenerate
                );

        if (
                documentFileIds.isEmpty()
                        && encodedImages.isEmpty()
        ) {
            return messages;
        }

        for (
                int index = messages.size() - 1;
                index >= 0;
                index--
        ) {
            OllamaChatMessage message =
                    messages.get(index);

            if (!USER_ROLE.equalsIgnoreCase(message.getRole())) {
                continue;
            }

            String content =
                    message.getContent();

            if (!documentFileIds.isEmpty()) {
                String searchQuestion =
                        ragQueryRewriteService.rewrite(
                                messages,
                                content
                        );

                List<String> searchQuestions =
                        ragMultiQueryService.generate(
                                searchQuestion
                        );

                content =
                        filePromptBuilder.build(
                                roomId,
                                documentFileIds,
                                content,
                                searchQuestion,
                                searchQuestions
                        );
            }

            List<String> images =
                    encodedImages.isEmpty()
                            ? null
                            : encodedImages;

            messages.set(
                    index,
                    new OllamaChatMessage(
                            message.getRole(),
                            content,
                            images
                    )
            );

            break;
        }

        log.info(
                "대화 메모리 기반 AI 메시지 생성 완료. roomId={}, messageCount={}, regenerate={}",
                roomId,
                messages.size(),
                regenerate
        );

        return messages;
    }
}