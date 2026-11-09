package com.agent.aiagent.domain.file.dto;

import com.agent.aiagent.domain.file.entity.GeneratedFile;

import java.time.LocalDateTime;

public record GeneratedFileResponse(
        String id,
        String originalName,
        String contentType,
        String extension,
        long size,
        LocalDateTime createdAt
) {

    public static GeneratedFileResponse from(
            GeneratedFile generatedFile
    ) {
        return new GeneratedFileResponse(
                generatedFile.getId(),
                generatedFile.getOriginalName(),
                generatedFile.getContentType(),
                generatedFile.getExtension(),
                generatedFile.getSize(),
                generatedFile.getCreatedAt()
        );
    }
}