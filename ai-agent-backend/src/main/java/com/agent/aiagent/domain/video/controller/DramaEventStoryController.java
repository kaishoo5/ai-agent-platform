
package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.video.service.DramaEventStoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/videos/drama/story")
public class DramaEventStoryController {

    private final DramaEventStoryService storyService;

    @PostMapping("/preview")
    public DramaEventStoryService.StoryResponse preview(
            @RequestBody DramaEventStoryService.StoryRequest request
    ) {
        return storyService.generate(request);
    }
}
