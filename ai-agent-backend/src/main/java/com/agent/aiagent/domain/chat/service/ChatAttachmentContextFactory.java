package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.dto.ChatRequest;
import com.agent.aiagent.domain.chat.model.ChatAttachmentContext;
import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.file.service.ChatFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentContextFactory {

    private final ChatFileRepository chatFileRepository;
    private final ChatFileService chatFileService;
    private final ChatImageEncoder chatImageEncoder;

    public ChatAttachmentContext create(
            ChatRequest request
    ) {
        List<ChatFile> chatFiles =
                findRequestFiles(
                        request
                );

        List<String> documentFileIds =
                chatFiles.stream()
                        .filter(file ->
                                !chatImageEncoder.isImage(file)
                        )
                        .map(ChatFile::getId)
                        .toList();

        List<String> encodedImages =
                chatFiles.stream()
                        .filter(chatImageEncoder::isImage)
                        .map(chatImageEncoder::encode)
                        .toList();

        log.info(
                "채팅 첨부파일 컨텍스트 생성 완료. roomId={}, documentCount={}, imageCount={}",
                request.getRoomId(),
                documentFileIds.size(),
                encodedImages.size()
        );

        return new ChatAttachmentContext(
                documentFileIds,
                encodedImages
        );
    }

    private List<ChatFile> findRequestFiles(
            ChatRequest request
    ) {
        if (
                request.getFileIds() == null
                        || request.getFileIds().isEmpty()
        ) {
            return chatFileRepository
                    .findAllByRoomIdOrderByCreatedAtAsc(
                            request.getRoomId()
                    );
        }

        return chatFileService.findFiles(
                request.getRoomId(),
                request.getFileIds()
        );
    }
}