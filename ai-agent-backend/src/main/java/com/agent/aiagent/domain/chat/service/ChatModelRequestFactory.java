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

import java.util.ArrayList;
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

        String videoToolName = detectVideoGenerationTool(request, attachmentContext.documentFileIds());

        final String selectedVideoToolName = videoToolName;

        List<ChatModelTool> tools =
                toolRegistry.getSpecifications()
                        .stream()
                        .filter(specification ->
                                (selectedVideoToolName != null
                                        ? selectedVideoToolName.equals(specification.name())
                                        : !hasZipAttachment
                                        || ZIP_ATTACHMENT_ALLOWED_TOOL_NAMES.contains(
                                        specification.name()
                                ))
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

        if (videoToolName != null && tools.isEmpty()) {
            log.warn("영상 생성 도구를 찾지 못했습니다. toolName={}", videoToolName);
            videoToolName = null;
        }

        if (videoToolName != null) {
            List<ChatModelMessage> directedMessages = new ArrayList<>(messages);
            directedMessages.add(new ChatModelMessage("system", """
                    [현재 요청의 영상 생성 실행]
                    사용자가 첨부한 영상으로 결과 영상을 만들어 달라고 명확히 요청했습니다.
                    지금 제공된 영상 생성 도구를 호출하세요. 도구 호출 없이 생성 불가능하다고 답하지 마세요.
                    episodeFileIds/fileId는 현재 첨부파일 컨텍스트에 있는 실제 ID만 사용하세요.
                    이전 대화 요약이나 메모리에 나온 영상 파일명은 이번 실행 결과가 아닙니다.
                    실제 도구 결과를 확인하기 전에는 성공했다고 답하지 마세요.
                    모드, 길이, 회차 순서, 쇼츠 개수 등은 현재 사용자 요청을 우선하세요.
                    """));
            messages = List.copyOf(directedMessages);
        }

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

    private String detectVideoGenerationTool(ChatRequest request, List<String> fileIds) {
        if (request == null || request.getMessages() == null || fileIds == null || fileIds.isEmpty()) {
            return null;
        }

        String question = request.getMessages().stream()
                .filter(message -> message != null && "user".equalsIgnoreCase(message.getRole()))
                .reduce((first, second) -> second)
                .map(message -> message.getContent())
                .orElse("");

        if (question == null || question.isBlank()) {
            return null;
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        boolean creation = normalized.contains("만들") || normalized.contains("생성")
                || normalized.contains("제작") || normalized.contains("편집")
                || normalized.contains("create") || normalized.contains("generate");
        if (!creation) {
            return null;
        }

        boolean hasVideo = false;
        for (String fileId : fileIds) {
            if (fileId == null || fileId.isBlank()) {
                continue;
            }
            ChatFile file = chatFileRepository.findByIdAndRoomId(fileId, request.getRoomId()).orElse(null);
            if (file == null || file.getExtension() == null) {
                continue;
            }
            String extension = file.getExtension().trim().toLowerCase(Locale.ROOT);
            if (Set.of("mp4", "mkv", "mov", "avi", "webm", "m4v").contains(extension)) {
                hasVideo = true;
                break;
            }
        }
        if (!hasVideo) {
            return null;
        }

        if (normalized.contains("쇼츠") || normalized.contains("shorts") || normalized.contains("short-form")) {
            return "video_shorts_generate";
        }
        if (normalized.contains("드라마") && (normalized.contains("요약") || normalized.contains("줄거리"))) {
            return "drama_summary_generate";
        }
        if (normalized.contains("영상") && normalized.contains("요약")) {
            return "video_summary_generate";
        }
        return null;
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