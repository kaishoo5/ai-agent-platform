package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatFileStatusService {

    private final ChatFileRepository chatFileRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAnalyzing(
            String fileId
    ) {
        findFile(
                fileId
        ).markAnalyzing();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            String fileId
    ) {
        findFile(
                fileId
        ).markFailed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(
            String fileId
    ) {
        findFile(
                fileId
        ).markCancelled();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(
            String fileId
    ) {
        ChatFile chatFile =
                chatFileRepository.findById(
                                fileId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "파일을 찾을 수 없습니다: "
                                                + fileId
                                )
                        );

        chatFile.markCompleted();
    }

    private ChatFile findFile(
            String fileId
    ) {
        return chatFileRepository.findById(
                        fileId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "파일을 찾을 수 없습니다: "
                                        + fileId
                        )
                );
    }
}
