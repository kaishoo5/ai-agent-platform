package com.agent.aiagent.domain.gemini.service;

import com.agent.aiagent.infra.gemini.GeminiClient;
import com.agent.aiagent.infra.gemini.GeminiFileClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GeminiFileService {

    private final GeminiFileClient geminiFileClient;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public GeminiFileService(
            GeminiFileClient geminiFileClient,
            GeminiClient geminiClient,
            ObjectMapper objectMapper
    ) {
        this.geminiFileClient = geminiFileClient;
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    public GeminiFileUploadResponse upload(MultipartFile file) {

        String response = geminiFileClient.upload(file);

        JsonNode root = objectMapper.readTree(response);
        JsonNode fileNode = root.path("file");

        if (fileNode.isMissingNode() || fileNode.isNull()) {
            throw new IllegalStateException(
                    "Gemini 파일 업로드 응답에 file 정보가 없습니다."
            );
        }

        String name = fileNode.path("name").asText("");
        String uri = fileNode.path("uri").asText("");

        if (name.isBlank() || uri.isBlank()) {
            throw new IllegalStateException(
                    "Gemini 파일 업로드 응답에 name 또는 uri가 없습니다."
            );
        }

        return new GeminiFileUploadResponse(
                name,
                uri,
                fileNode.path("displayName").asText(""),
                fileNode.path("mimeType").asText(""),
                fileNode.path("sizeBytes").asText(""),
                fileNode.path("state").asText("")
        );
    }

    /** 업로드가 ACTIVE인 MP4의 fileUri로 Gemini 영상 분석을 수행한다. */
    public VideoAnalysisResponse analyze(String fileUri, String prompt) {
        JsonNode response = geminiClient.analyzeVideo(fileUri, "video/mp4", prompt);
        JsonNode candidates = response.path("candidates");
        StringBuilder answer = new StringBuilder();
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode text = part.path("text");
                    if (text.isTextual()) {
                        answer.append(text.asText());
                    }
                }
            }
        }
        if (answer.isEmpty()) {
            throw new IllegalStateException("Gemini 영상 분석 결과에 텍스트가 없습니다: " + response);
        }
        return new VideoAnalysisResponse(fileUri, answer.toString());
    }

    public VideoAnalysisResponse analyzeSegment(
            String fileUri, String prompt, long startSeconds, long endSeconds
    ) {
        JsonNode response = geminiClient.analyzeVideoSegment(
                fileUri, "video/mp4", prompt, startSeconds, endSeconds
        );
        StringBuilder answer = new StringBuilder();
        JsonNode candidates = response.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.path("text").isTextual()) {
                        answer.append(part.path("text").asText());
                    }
                }
            }
        }
        if (answer.isEmpty()) {
            throw new IllegalStateException("Gemini 구간 분석 결과에 텍스트가 없습니다: " + response);
        }
        return new VideoAnalysisResponse(fileUri, answer.toString());
    }

    public record VideoAnalysisRequest(String fileUri, String prompt) {
    }

    public record VideoAnalysisResponse(String fileUri, String content) {
    }

    public record GeminiFileUploadResponse(
            String name,
            String uri,
            String displayName,
            String mimeType,
            String sizeBytes,
            String state
    ) {
    }
}
