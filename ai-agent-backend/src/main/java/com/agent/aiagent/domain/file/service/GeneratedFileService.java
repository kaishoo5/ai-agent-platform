package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.entity.GeneratedFile;
import com.agent.aiagent.domain.file.repository.GeneratedFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GeneratedFileService {

    private final GeneratedFileRepository generatedFileRepository;

    @Value("${app.file.generated-dir}")
    private String generatedDirectory;

    @Transactional
    public GeneratedFile save(
            String roomId,
            String originalName,
            byte[] content,
            String contentType
    ) {
        validateRoomId(
                roomId
        );

        if (!StringUtils.hasText(originalName)) {
            throw new IllegalArgumentException(
                    "생성할 파일명이 없습니다."
            );
        }

        if (content == null) {
            throw new IllegalArgumentException(
                    "생성할 파일 내용이 없습니다."
            );
        }

        String cleanOriginalName =
                StringUtils.cleanPath(
                        originalName
                );

        String extension =
                getExtension(
                        cleanOriginalName
                );

        String storedName =
                UUID.randomUUID()
                        + "."
                        + extension;

        Path roomDirectory =
                Path.of(
                                generatedDirectory,
                                roomId
                        )
                        .toAbsolutePath()
                        .normalize();

        Path targetPath =
                roomDirectory
                        .resolve(
                                storedName
                        )
                        .normalize();

        if (!targetPath.startsWith(roomDirectory)) {
            throw new IllegalArgumentException(
                    "유효하지 않은 파일 경로입니다."
            );
        }

        try {
            Files.createDirectories(
                    roomDirectory
            );

            Files.write(
                    targetPath,
                    content
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "생성 파일 저장 중 오류가 발생했습니다.",
                    exception
            );
        }

        GeneratedFile generatedFile =
                new GeneratedFile(
                        roomId,
                        cleanOriginalName,
                        storedName,
                        targetPath.toString(),
                        contentType,
                        extension,
                        content.length
                );

        try {
            return generatedFileRepository.saveAndFlush(
                    generatedFile
            );
        } catch (RuntimeException exception) {
            deleteStoredFile(
                    targetPath
            );

            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public GeneratedFile findById(
            String fileId
    ) {
        if (!StringUtils.hasText(fileId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "파일 ID가 없습니다."
            );
        }

        return generatedFileRepository
                .findById(
                        fileId
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "생성된 파일을 찾을 수 없습니다."
                        )
                );
    }

    @Transactional(readOnly = true)
    public Resource loadAsResource(
            GeneratedFile generatedFile
    ) {
        Path path =
                Path.of(
                                generatedFile.getStoredPath()
                        )
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "저장된 파일을 찾을 수 없습니다."
            );
        }

        try {
            Resource resource =
                    new UrlResource(
                            path.toUri()
                    );

            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "저장된 파일을 읽을 수 없습니다."
                );
            }

            return resource;
        } catch (MalformedURLException exception) {
            throw new IllegalStateException(
                    "생성 파일 경로를 읽지 못했습니다.",
                    exception
            );
        }
    }

    private String getExtension(
            String filename
    ) {
        int extensionIndex =
                filename.lastIndexOf(
                        "."
                );

        if (
                extensionIndex < 0
                        || extensionIndex
                        == filename.length() - 1
        ) {
            throw new IllegalArgumentException(
                    "파일 확장자가 없습니다."
            );
        }

        return filename
                .substring(
                        extensionIndex + 1
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private void validateRoomId(
            String roomId
    ) {
        if (!StringUtils.hasText(roomId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "채팅방 ID가 없습니다."
            );
        }
    }

    private void deleteStoredFile(
            Path path
    ) {
        try {
            Files.deleteIfExists(
                    path
            );
        } catch (IOException ignored) {
        }
    }

    @Transactional
    public void attachPendingFilesToMessage(
            String roomId,
            String messageId
    ) {
        if (!StringUtils.hasText(roomId)) {
            return;
        }

        if (!StringUtils.hasText(messageId)) {
            return;
        }

        List<GeneratedFile> generatedFiles =
                generatedFileRepository
                        .findAllByRoomIdAndMessageIdIsNullOrderByCreatedAtAsc(
                                roomId
                        );

        if (generatedFiles.isEmpty()) {
            return;
        }

        generatedFiles.forEach(generatedFile ->
                generatedFile.attachMessage(
                        messageId
                )
        );
    }
}