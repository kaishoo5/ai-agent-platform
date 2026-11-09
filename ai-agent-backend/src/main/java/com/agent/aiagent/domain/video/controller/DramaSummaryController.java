
package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/videos/drama")
@RequiredArgsConstructor
public class DramaSummaryController {

    private final ChatFileRepository chatFileRepository;

    @GetMapping("/{fileId}/{fileName}")
    public ResponseEntity<Resource> getDramaVideo(
            @PathVariable String fileId,
            @PathVariable String fileName,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        ChatFile file = chatFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("원본 파일을 찾을 수 없습니다."));

        if (file.getStoredPath() == null || file.getStoredPath().isBlank()) {
            throw new IllegalStateException("원본 파일 경로가 없습니다.");
        }

        if (!fileName.matches("drama_(story|narration)_[0-9a-fA-F-]{36}\\.mp4")) {
            throw new IllegalArgumentException("유효하지 않은 드라마 요약 파일명입니다.");
        }

        Path parent = Path.of(file.getStoredPath())
                .toAbsolutePath()
                .normalize()
                .getParent();

        if (parent == null) {
            throw new IllegalStateException("영상 저장 폴더가 없습니다.");
        }

        Path output = parent.resolve(fileName).normalize();

        if (!output.startsWith(parent) || !Files.isRegularFile(output)) {
            throw new IllegalArgumentException("생성된 드라마 요약 영상을 찾을 수 없습니다.");
        }

        try {
            Resource resource = new UrlResource(output.toUri());

            ContentDisposition disposition = (download
                    ? ContentDisposition.attachment()
                    : ContentDisposition.inline())
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(resource);

        } catch (MalformedURLException exception) {
            throw new IllegalStateException("영상 파일을 읽을 수 없습니다.", exception);
        }
    }
}