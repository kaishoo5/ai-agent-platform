package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.video.service.DramaEventTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/videos/drama/timeline")
public class DramaEventTimelinePreviewController {

    private final DramaEventTimelineService timelineService;

    @PostMapping("/preview")
    public DramaEventTimelineService.TimelineResponse preview(@RequestBody PreviewRequest request) {
        if (request == null || request.fileId() == null || request.fileId().isBlank()) {
            throw new IllegalArgumentException("fileId가 필요합니다.");
        }
        return timelineService.preview(request.fileId());
    }

    public record PreviewRequest(String fileId) { }
}
