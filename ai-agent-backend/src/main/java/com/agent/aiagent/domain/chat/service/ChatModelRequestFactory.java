package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatAttachmentContext;
import com.agent.aiagent.domain.chat.model.ChatExecutionContext;
import com.agent.aiagent.domain.chat.model.ChatMessageContext;
import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.tool.service.ToolRegistry;
import com.agent.aiagent.provider.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelRequestFactory {

    private static final Set<String> ZIP_ATTACHMENT_ALLOWED_TOOL_NAMES =
            Set.of(
                    "attachment_project_structure",
                    "attachment_codebase_overview",
                    "attachment_search",
                    "attachment_read_source",
                    "web_search",
                    "calculator",
                    "current_time"
            );

    private final ChatAttachmentContextFactory chatAttachmentContextFactory;
    private final ChatMessageContextFactory chatMessageContextFactory;
    private final ToolRegistry toolRegistry;
    private final ChatFileRepository chatFileRepository;
    private final ChatModelToolMapper chatModelToolMapper;

    public ChatModelRequest create(
            ChatRequest request
    ) {
        return createContext(
                request
        ).modelRequest();
    }

    public ChatExecutionContext createContext(
            ChatRequest request
    ) {
        return createContext(
                request,
                null
        );
    }

    public ChatExecutionContext createContext(
            ChatRequest request,
            AgentProgressReporter progressReporter
    ) {
        ChatAttachmentContext attachmentContext =
                chatAttachmentContextFactory.create(
                        request
                );

        ChatMessageContext messageContext =
                chatMessageContextFactory.createContext(
                        request.getRoomId(),
                        request.isRegenerate(),
                        attachmentContext.documentFileIds(),
                        attachmentContext.encodedImages(),
                        progressReporter
                );

        List<ChatModelMessage> messages =
                messageContext.messages();

        boolean hasZipAttachment =
                hasZipAttachment(
                        request.getRoomId(),
                        attachmentContext.documentFileIds()
                );

        List<ChatModelTool> tools =
                toolRegistry.getSpecifications()
                        .stream()
                        .filter(specification ->
                                !hasZipAttachment
                                        || ZIP_ATTACHMENT_ALLOWED_TOOL_NAMES.contains(
                                        specification.name()
                                )
                        )
                        .map(
                                chatModelToolMapper::map
                        )
                        .toList();

        ChatModelType modelType =
                attachmentContext.hasImages()
                        ? ChatModelType.VISION
                        : ChatModelType.TEXT;

        log.info(
                "AI 요청 생성 완료. "
                        + "roomId={}, modelType={}, documentCount={}, "
                        + "imageCount={}, sourceCount={}, "
                        + "hasZipAttachment={}, toolCount={}, tools={}",
                request.getRoomId(),
                modelType,
                attachmentContext.documentFileIds().size(),
                attachmentContext.encodedImages().size(),
                messageContext.sources().size(),
                hasZipAttachment,
                tools.size(),
                tools.stream()
                        .map(ChatModelTool::name)
                        .toList()
        );

        ChatModelRequest modelRequest =
                new ChatModelRequest(
                        modelType,
                        messages,
                        tools
                );

        return new ChatExecutionContext(
                modelRequest,
                messageContext.sources(),
                attachmentContext.documentFileIds()
        );
    }

    private boolean hasZipAttachment(
            String roomId,
            List<String> documentFileIds
    ) {
        if (
                roomId == null
                        || roomId.isBlank()
                        || documentFileIds == null
                        || documentFileIds.isEmpty()
        ) {
            return false;
        }

        for (String fileId : documentFileIds) {
            if (
                    fileId == null
                            || fileId.isBlank()
            ) {
                continue;
            }

            ChatFile chatFile =
                    chatFileRepository.findByIdAndRoomId(
                                    fileId,
                                    roomId
                            )
                            .orElse(
                                    null
                            );

            if (
                    chatFile == null
                            || chatFile.getExtension() == null
            ) {
                continue;
            }

            if (
                    "zip".equals(
                            chatFile.getExtension()
                                    .trim()
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                    )
            ) {
                return true;
            }
        }

        return false;
    }
}