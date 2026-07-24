package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
public class CurrentTimeTool implements AgentTool {

    private static final String DEFAULT_ZONE_ID =
            "Asia/Seoul";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss z"
            );

    @Override
    public String getName() {
        return "current_time";
    }

    @Override
    public String getDescription() {
        return "지정한 시간대의 현재 날짜와 시간을 조회합니다. "
                + "인자로 zoneId를 받을 수 있으며, 기본값은 Asia/Seoul입니다.";
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String zoneId =
                getZoneId(
                        arguments
                );

        try {
            ZonedDateTime currentTime =
                    ZonedDateTime.now(
                            ZoneId.of(zoneId)
                    );

            String formattedTime =
                    currentTime.format(
                            DATE_TIME_FORMATTER
                    );

            log.info(
                    "현재 시간 Tool 실행 완료. zoneId={}, currentTime={}",
                    zoneId,
                    formattedTime
            );

            return ToolResult.success(
                    formattedTime
            );
        } catch (DateTimeException exception) {
            log.warn(
                    "유효하지 않은 시간대가 입력되었습니다. zoneId={}",
                    zoneId
            );

            return ToolResult.failure(
                    "유효하지 않은 시간대입니다: "
                            + zoneId
            );
        }
    }

    private String getZoneId(
            Map<String, Object> arguments
    ) {
        if (
                arguments == null
                        || arguments.get("zoneId") == null
        ) {
            return DEFAULT_ZONE_ID;
        }

        String zoneId =
                arguments.get("zoneId")
                        .toString()
                        .trim();

        if (zoneId.isEmpty()) {
            return DEFAULT_ZONE_ID;
        }

        return zoneId;
    }
}