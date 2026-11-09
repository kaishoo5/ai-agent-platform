package com.agent.aiagent.domain.gemini.controller;

import com.agent.aiagent.domain.gemini.service.GeminiTestService;
import com.agent.aiagent.domain.gemini.service.GeminiTestService.GeminiTestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gemini")
public class GeminiTestController {

    private final GeminiTestService geminiTestService;

    @PostMapping("/test")
    public GeminiTestResponse test() {
        return geminiTestService.test();
    }
}
