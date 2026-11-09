package com.agent.aiagent.infra.gemini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class GeminiFileClient {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiFileClient.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String baseUrl;

    public GeminiFileClient(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.base-url}") String baseUrl
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.webClient = WebClient.builder().build();
    }

    public String upload(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 없습니다.");
        }

        String filename = file.getOriginalFilename();

        if (filename == null ||
                !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalArgumentException("MP4 파일만 업로드할 수 있습니다.");
        }

        long fileSize = file.getSize();

        if (fileSize > 2L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("2GB 이하 파일만 업로드할 수 있습니다.");
        }

        log.info("Gemini 파일 업로드 시작. filename={}, size={}",
                filename, fileSize);

        String uploadUrl = startUpload(filename, fileSize);

        String result = uploadBinary(uploadUrl, file);

        log.info("Gemini 파일 업로드 완료. filename={}", filename);

        return result;
    }

    /** 기존 채팅 파일 저장 경로의 MP4를 Gemini에 직접 업로드한다. */
    public String upload(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("영상 파일을 찾을 수 없습니다.");
        }
        String filename = path.getFileName().toString();
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalArgumentException("Gemini 업로드는 현재 MP4만 지원합니다.");
        }
        try {
            long size = Files.size(path);
            if (size == 0 || size > 2L * 1024 * 1024 * 1024) {
                throw new IllegalArgumentException("Gemini 업로드는 1바이트 이상 2GB 이하 MP4만 지원합니다.");
            }
            String url = startUpload(filename, size);
            String response = webClient.post()
                    .uri(URI.create(url))
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .contentLength(size)
                    .body(BodyInserters.fromResource(new FileSystemResource(path)))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Gemini 파일 업로드 응답이 비어 있습니다.");
            }
            return response;
        } catch (IOException exception) {
            throw new IllegalStateException("영상 파일을 읽을 수 없습니다.", exception);
        }
    }

    /** Gemini Files API에서 파일 처리 상태를 조회한다. */
    public String getFile(String fileName) {
        if (fileName == null || !fileName.matches("files/[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("올바르지 않은 Gemini 파일 이름입니다.");
        }
        return webClient.get()
                .uri(baseUrl + "/v1beta/" + fileName)
                .header("x-goog-api-key", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String startUpload(String filename, long fileSize) {

        String url = baseUrl + "/upload/v1beta/files";

        Map<String, Object> requestBody = Map.of(
                "file", Map.of(
                        "display_name", filename
                )
        );

        URI uploadUri = webClient.post()
                .uri(url)
                .header("x-goog-api-key", apiKey)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length",
                        String.valueOf(fileSize))
                .header("X-Goog-Upload-Header-Content-Type", "video/mp4")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchangeToMono(response -> {

                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(
                                        new IllegalStateException(
                                                "Gemini 업로드 시작 실패. HTTP "
                                                        + response.statusCode()
                                                        + ", body=" + body
                                        )
                                ));
                    }

                    String uploadUrl = response.headers()
                            .asHttpHeaders()
                            .getFirst("X-Goog-Upload-URL");

                    if (uploadUrl == null || uploadUrl.isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "Gemini 업로드 URL이 응답에 없습니다."
                        ));
                    }

                    return response.releaseBody()
                            .thenReturn(URI.create(uploadUrl));
                })
                .block();

        if (uploadUri == null ||
                !"https".equalsIgnoreCase(uploadUri.getScheme()) ||
                !"generativelanguage.googleapis.com".equalsIgnoreCase(
                        uploadUri.getHost())) {
            throw new IllegalStateException("Gemini 업로드 URL이 올바르지 않습니다.");
        }

        return uploadUri.toString();
    }

    private String uploadBinary(String uploadUrl, MultipartFile file) {

        try {
            byte[] bytes = file.getBytes();

            ByteArrayResource resource = new ByteArrayResource(bytes);

            String response = webClient.post()
                    .uri(URI.create(uploadUrl))
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .contentLength(bytes.length)
                    .body(BodyInserters.fromResource(resource))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "Gemini 파일 업로드 응답이 비어 있습니다."
                );
            }

            return response;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "MP4 파일을 읽는 중 오류가 발생했습니다.", e
            );

        } catch (WebClientResponseException e) {
            throw new IllegalStateException(
                    "Gemini 파일 전송 실패. HTTP "
                            + e.getStatusCode()
                            + ", body=" + e.getResponseBodyAsString(),
                    e
            );
        }
    }
}
