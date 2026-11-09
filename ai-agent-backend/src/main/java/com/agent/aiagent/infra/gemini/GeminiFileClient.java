package com.agent.aiagent.infra.gemini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
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
