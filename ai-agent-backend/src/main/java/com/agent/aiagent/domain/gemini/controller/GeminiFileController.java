package com.agent.aiagent.domain.gemini.controller;

import com.agent.aiagent.domain.gemini.service.GeminiFileService;
import com.agent.aiagent.domain.gemini.service.GeminiFileService.GeminiFileUploadResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
}
