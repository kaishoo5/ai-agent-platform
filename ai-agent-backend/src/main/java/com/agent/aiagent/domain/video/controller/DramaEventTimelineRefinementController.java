
package com.agent.aiagent.domain.video.controller;

import com.agent.aiagent.domain.video.service.DramaEventTimelineRefinementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/videos/drama/timeline")
public class DramaEventTimelineRefinementController {

    private final DramaEventTimelineRefinementService refinementService;

    @PostMapping("/refine")
    public DramaEventTimelineRefinementService.RefineResponse refine(
            @RequestBody DramaEventTimelineRefinementService.RefineRequest request
    ) {
        return refinementService.refine(request);
    }
}
