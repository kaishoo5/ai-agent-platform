package com.agent.aiagent.domain.file.controller;

import com.agent.aiagent.domain.file.entity.GeneratedFile;
import com.agent.aiagent.domain.file.service.GeneratedFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/generated-files")
@RequiredArgsConstructor
public class GeneratedFileController {

    private final GeneratedFileService generatedFileService;

    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable String fileId
    ) {
        GeneratedFile generatedFile =
                generatedFileService.findById(
                        fileId
                );

        Resource resource =
                generatedFileService.loadAsResource(
                        generatedFile
                );

        MediaType mediaType =
                resolveMediaType(
                        generatedFile.getContentType()
                );

        ContentDisposition contentDisposition =
                ContentDisposition
                        .attachment()
                        .filename(
                                generatedFile.getOriginalName(),
                                StandardCharsets.UTF_8
                        )
                        .build();

        return ResponseEntity
                .ok()
                .contentType(
                        mediaType
                )
                .contentLength(
                        generatedFile.getSize()
                )
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition.toString()
                )
                .body(
                        resource
                );
    }

    private MediaType resolveMediaType(
            String contentType
    ) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        try {
            return MediaType.parseMediaType(
                    contentType
            );
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}