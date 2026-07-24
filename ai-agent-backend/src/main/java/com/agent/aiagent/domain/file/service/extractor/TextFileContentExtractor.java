package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Slf4j
@Component
public class TextFileContentExtractor implements FileContentExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(
                    "txt",
                    "md",
                    "java",
                    "js",
                    "ts",
                    "tsx",
                    "json",
                    "sql",
                    "xml",
                    "yaml",
                    "yml",
                    "properties"
            );

    @Override
    public boolean supports(String extension) {
        if (extension == null) {
            return false;
        }

        return SUPPORTED_EXTENSIONS.contains(
                extension.toLowerCase()
        );
    }

    @Override
    public String extract(ChatFile chatFile) {
        try {
            return Files.readString(
                    Path.of(chatFile.getStoredPath()),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            log.error(
                    "텍스트 파일 읽기에 실패했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "텍스트 파일을 읽을 수 없습니다."
            );
        }
    }
}