package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@Slf4j
@Component
public class ChatImageEncoder {

    public boolean isImage(ChatFile file) {
        return switch (file.getExtension()) {
            case "png",
                 "jpg",
                 "jpeg",
                 "gif",
                 "webp" -> true;
            default -> false;
        };
    }

    public String encode(ChatFile file) {
        try {
            byte[] bytes =
                    Files.readAllBytes(
                            Path.of(
                                    file.getStoredPath()
                            )
                    );

            return Base64.getEncoder()
                    .encodeToString(
                            bytes
                    );
        } catch (IOException exception) {
            log.error(
                    "이미지 파일을 읽지 못했습니다. fileId={}, storedPath={}",
                    file.getId(),
                    file.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "이미지 파일을 읽지 못했습니다.",
                    exception
            );
        }
    }
}