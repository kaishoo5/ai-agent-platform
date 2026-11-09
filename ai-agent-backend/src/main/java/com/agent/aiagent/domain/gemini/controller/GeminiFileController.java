package com.agent.aiagent.domain.gemini.controller;

import com.agent.aiagent.domain.gemini.service.GeminiFileService;
import com.agent.aiagent.domain.gemini.service.GeminiFileService.GeminiFileUploadResponse;
import com.agent.aiagent.domain.gemini.service.GeminiFileService.VideoAnalysisRequest;
import com.agent.aiagent.domain.gemini.service.GeminiFileService.VideoAnalysisResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/gemini/files")
public class GeminiFileController {

    private final GeminiFileService geminiFileService;

    public GeminiFileController(GeminiFileService geminiFileService) {
        this.geminiFileService = geminiFileService;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public GeminiFileUploadResponse upload(
            @RequestParam("file") MultipartFile file
    ) {
        return geminiFileService.upload(file);
    }
    @PostMapping("/analyze")
    public VideoAnalysisResponse analyze(@RequestBody VideoAnalysisRequest request) {
        return geminiFileService.analyze(request.fileUri(), request.prompt());
    }
}
