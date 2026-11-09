package com.agent.aiagent.domain.video.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class VideoShortsTitleRenderer {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 240;
    private static final int FONT_SIZE = 68;
    private static final int HORIZONTAL_PADDING = 60;

    public Path render(String title, Path outputDirectory) {
        if (title == null || title.isBlank()) {
            return null;
        }

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("쇼츠 제목 출력 폴더 생성 실패", exception);
        }

        Path outputPath;
        try {
            outputPath = Files.createTempFile(outputDirectory, "shorts_title_", ".png");
        } catch (IOException exception) {
            throw new IllegalStateException("쇼츠 제목 PNG 경로 생성 실패", exception);
        }

        String html = buildHtml(title);

        // Playwright 객체는 스레드 안전하지 않으므로 각 호출에서 독립적으로 사용한다.
        // 기능 확인 후 성능이 필요하면 전용 단일 스레드 렌더러로 브라우저 재사용 가능.
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            try {
                Page page = browser.newPage(new Browser.NewPageOptions()
                        .setViewportSize(WIDTH, HEIGHT)
                        .setDeviceScaleFactor(1));
                try {
                    page.setContent(html);
                    page.evaluate("() => document.fonts.ready");
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(outputPath)
                            .setOmitBackground(true));
                } finally {
                    page.close();
                }
            } finally {
                browser.close();
            }
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(outputPath);
            } catch (IOException cleanupException) {
                log.warn("실패한 제목 PNG 삭제 실패: {}", outputPath, cleanupException);
            }
            throw new IllegalStateException("쇼츠 제목 PNG 생성 실패 (Chromium 설치 상태 확인 필요)", exception);
        }

        log.info("쇼츠 제목 PNG 생성 완료. title={}, outputPath={}", title, outputPath);
        return outputPath;
    }

    private String buildHtml(String title) {
        String safeTitle = escapeHtml(title);
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                    <meta charset="UTF-8">
                    <style>
                        * { box-sizing: border-box; }
                        html, body {
                            width: %dpx;
                            height: %dpx;
                            margin: 0;
                            padding: 0;
                            background: transparent;
                        }
                        .title-container {
                            width: 100%%;
                            height: 100%%;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            padding: 0 %dpx;
                        }
                        .title {
                            max-width: 100%%;
                            overflow: hidden;
                            white-space: nowrap;
                            text-overflow: ellipsis;
                            font-family: "Malgun Gothic", "Segoe UI Emoji", sans-serif;
                            font-size: %dpx;
                            font-weight: 700;
                            line-height: 1.4;
                            color: #ffffff;
                            -webkit-text-stroke: 6px rgba(0, 0, 0, 0.86);
                            paint-order: stroke fill;
                        }
                    </style>
                </head>
                <body>
                    <div class="title-container"><div class="title">%s</div></div>
                </body>
                </html>
                """.formatted(WIDTH, HEIGHT, HORIZONTAL_PADDING, FONT_SIZE, safeTitle);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
