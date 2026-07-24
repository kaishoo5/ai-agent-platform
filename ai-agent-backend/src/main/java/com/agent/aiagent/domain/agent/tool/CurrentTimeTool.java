package com.agent.aiagent.domain.agent.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class CurrentTimeTool implements AgentTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss"
            );

    @Override
    public String getName() {
        return "current_time";
    }

    @Override
    public String getDescription() {
        return """
                현재 날짜와 시간을 조회합니다.
                사용자가 현재 시간, 날짜, 시각을 물어볼 때 사용합니다.
                """;
    }

    @Override
    public boolean supports(
            String question
    ) {
        String lower =
                question.toLowerCase();

        return lower.contains("시간")
                || lower.contains("몇시")
                || lower.contains("몇 시")
                || lower.contains("시각")
                || lower.contains("날짜")
                || lower.contains("오늘");
    }

    @Override
    public String execute(
            String question
    ) {
        return LocalDateTime.now()
                .format(
                        FORMATTER
                );
    }
}