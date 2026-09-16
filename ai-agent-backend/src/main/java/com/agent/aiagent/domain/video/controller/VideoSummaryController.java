package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.video.service.VideoSummaryFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/videos/summaries")
@RequiredArgsConstructor
public class VideoSummaryController {

    private final VideoSummaryFileService videoSummaryFileService;

    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> getSummaryVideo(
            @PathVariable String fileId,
            @RequestParam(
                    defaultValue = "false"
            ) boolean download
    ) {
        Resource resource =
                videoSummaryFileService.getSummaryVideo(
                        fileId
                );

        String fileName =
                videoSummaryFileService.getSummaryFileName(
                        fileId
                );

        ContentDisposition disposition =
                download
                        ? ContentDisposition
                        .attachment()
                        .filename(
                                fileName,
                                StandardCharsets.UTF_8
                        )
                        .build()
                        : ContentDisposition
                        .inline()
                        .filename(
                                fileName,
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