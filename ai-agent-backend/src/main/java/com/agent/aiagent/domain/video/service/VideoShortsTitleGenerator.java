package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.VideoHighlightSegment;
import com.agent.aiagent.provider.chat.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
public class VideoShortsTitleGenerator {

    private final ChatModelProvider chatModelProvider;
    private final boolean aiTitleEnabled;

    public VideoShortsTitleGenerator(
            ChatModelProvider chatModelProvider,
            @Value("${video.shorts.ai-title-enabled:false}") boolean aiTitleEnabled
    ) {
        this.chatModelProvider = chatModelProvider;
        this.aiTitleEnabled = aiTitleEnabled;
    }

    public String generate(VideoHighlightSegment segment) {
        String reason = segment.reason();
        String suggestedTitle = segment.suggestedTitle();
        if (StringUtils.hasText(suggestedTitle)) {
            String title = suggestedTitle.strip()
                    .replace("\"", "")
                    .replace("“", "")
                    .replace("”", "")
                    .lines().findFirst().orElse("").strip();
            if (StringUtils.hasText(title) && title.length() <= 40) {
                log.info("쇼츠 후보 추천 제목 사용. title={}", title);
                return title;
            }
        }
        if (!StringUtils.hasText(reason)) {
            return "영상 속 흥미로운 순간";
        }

        if (!aiTitleEnabled) {
            String title = buildLocalTitle(reason);
            log.info("쇼츠 제목 로컬 생성 완료. title={}", title);
            return title;
        }

        String prompt = """
                아래 내용은 실제로 선택된 쇼츠 영상 구간의 분석 결과입니다.
                이 장면에만 어울리는 한국어 쇼츠 상단 제목을 하나 작성하세요.

                규칙:
                - 영상의 다른 장면이나 전체 줄거리를 제목에 섞지 마세요.
                - 등장인물, 사건, 수치 등은 제공된 내용에 근거해야 합니다.
                - 과장하거나 확인되지 않은 결과를 단정하지 마세요.
                - 짧고 읽기 쉽게, 24자 이내로 작성하세요.
                - 이모지와 해시태그, 따옴표, 설명 문장 없이 제목 한 줄만 반환하세요.

                선택된 장면 설명:
                """ + reason;

        try {
            ChatModelResponse response = chatModelProvider.chatOnce(
                    new ChatModelRequest(
                            ChatModelType.TEXT,
                            List.of(
                                    new ChatModelMessage(
                                            "system",
                                            "당신은 영상의 실제 장면 내용에 맞춰 짧은 제목을 작성하는 편집자입니다.",
                                            null
                                    ),
                                    new ChatModelMessage("user", prompt, null)
                            ),
                            List.of()
                    )
            );

            if (response != null && StringUtils.hasText(response.content())) {
                String title = response.content()
                        .strip()
                        .replace("\"", "")
                        .replace("“", "")
                        .replace("”", "")
                        .lines()
                        .findFirst()
                        .orElse("")
                        .strip();
                if (StringUtils.hasText(title) && title.length() <= 40) {
                    return title;
                }
            }
        } catch (Exception exception) {
            log.warn("쇼츠 제목 AI 생성 실패. 기본 제목으로 대체합니다. reason={}", reason, exception);
        }

        return buildLocalTitle(reason);
    }
    private String buildLocalTitle(String reason) {
        String normalized = reason.strip().replaceAll("\\s+", " ")
                .replaceAll("(하는|했던|보여주는) 장면$", "")
                .replaceAll("(장면|상황|모습)$", "").strip();
        if (normalized.isBlank()) {
            return "영상 속 흥미로운 순간";
        }

        // 사건을 설명하는 뒷부분을 우선한다. 앞 24자를 잘라 미완성 문장을 만들지 않는다.
        String[] eventMarkers = {
                "잘못 표기된", "잘못 나온", "웃음을 터뜨리는", "폭소하는",
                "찾아 헤매는", "뛰어다니며", "반갑게 인사", "깜짝 놀라는",
                "고백하는", "밝히는", "발견하는", "논쟁하는"
        };
        String subject = extractSubject(normalized);
        String event = normalized;
        for (String marker : eventMarkers) {
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                int from = Math.max(0, normalized.lastIndexOf(' ', index - 1) + 1);
                event = normalized.substring(from).strip();
                break;
            }
        }

        // 자주 등장하는 서술을 쇼츠용 짧은 표현으로 치환한다.
        event = event.replace("잘못 표기된 기사 제목을 보고 웃음을 터뜨리는", "기사 오보에 폭소")
                .replace("잘못 표기된 기사 제목", "기사 오보")
                .replace("웃음을 터뜨리는", "폭소")
                .replace("기자를 찾아 헤매는 갈등 상황과 주변 탐색", "기자를 찾아 헤매다")
                .replace("기자를 찾아 헤매는", "기자를 찾아 헤매다")
                .replace("반갑게 인사하는", "반갑게 인사")
                .replaceAll("(장면|상황|모습)$", "").strip();

        String candidate = event;
        if (!subject.isBlank() && !candidate.contains(subject)) {
            candidate = subject + " " + candidate;
        }
        if (candidate.length() <= 24 && !endsWithDanglingWord(candidate)) {
            return candidate;
        }

        // 전체 설명에서 완결된 짧은 절을 우선 사용한다.
        String[] clauses = normalized.split("[,，]|(?:던 중 )|(?:하며 )|(?:면서 )");
        for (int index = clauses.length - 1; index >= 0; index--) {
            String clause = clauses[index].strip()
                    .replaceAll("(장면|상황|모습)$", "").strip();
            if (clause.isBlank()) {
                continue;
            }
            String withSubject = !subject.isBlank() && !clause.contains(subject)
                    ? subject + " " + clause : clause;
            if (withSubject.length() <= 24 && !endsWithDanglingWord(withSubject)) {
                return withSubject;
            }
        }

        // 글자 수를 강제로 자르지 않는다. 완결된 설명을 우선하고, 긴 제목은 렌더러에서 줄바꿈한다.
        return candidate;
    }

    private String extractSubject(String reason) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^([가-힣A-Za-z0-9]{2,8})(?:과|와|이|가|은|는)\\s")
                .matcher(reason);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean endsWithDanglingWord(String title) {
        return title.matches(".*(?:을|를|은|는|이|가|에|에서|에게|으로|와|과|중|며|던)$");
    }
}
