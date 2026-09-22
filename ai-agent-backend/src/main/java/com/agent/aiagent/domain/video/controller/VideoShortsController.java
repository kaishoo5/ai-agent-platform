package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.video.service.VideoShortsFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/videos/shorts")
@RequiredArgsConstructor
public class VideoShortsController {

    private final VideoShortsFileService videoShortsFileService;

    @GetMapping("/{fileId}/{fileName}")
    public ResponseEntity<Resource> getShortsVideo(
            @PathVariable String fileId,
            @PathVariable String fileName,
            @RequestParam(
                    defaultValue = "false"
            ) boolean download
    ) {
        Resource resource =
                videoShortsFileService.getShortsVideo(
                        fileId,
                        fileName
                );

        String resolvedFileName =
                videoShortsFileService.getShortsFileName(
                        fileId,
                        fileName
                );

        ContentDisposition disposition =
                download
                        ? ContentDisposition
                        .attachment()
                        .filename(
                                resolvedFileName,
                                StandardCharsets.UTF_8
                        )
                        .build()
                        : ContentDisposition
                        .inline()
                        .filename(
                                resolvedFileName,
                                StandardCharsets.UTF_8
                        )
                        .build();

        return ResponseEntity
                .ok()
                .contentType(
                        MediaType.parseMediaType(
                                "video/mp4"
                        )
                )
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        disposition.toString()
                )
                .body(
                        resource
                );
    }
}