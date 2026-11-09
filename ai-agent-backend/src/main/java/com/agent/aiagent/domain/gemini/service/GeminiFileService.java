package com.agent.aiagent.domain.gemini.service;

import com.agent.aiagent.infra.gemini.GeminiFileClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GeminiFileService {

    private final GeminiFileClient geminiFileClient;
    private final ObjectMapper objectMapper;

    public GeminiFileService(
            GeminiFileClient geminiFileClient,
            ObjectMapper objectMapper
    ) {
        this.geminiFileClient = geminiFileClient;
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
