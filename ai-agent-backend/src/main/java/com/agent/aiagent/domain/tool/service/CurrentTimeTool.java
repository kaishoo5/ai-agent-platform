package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class CurrentTimeTool implements AgentTool {

    private static final String DEFAULT_ZONE_ID = "Asia/Seoul";

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss z"
            );

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "current_time",
                    "지정한 시간대의 현재 시간을 조회합니다.",
                    Map.of(
                            "zoneId",
                            new ToolParameter(
                                    "string",
                                    "IANA 시간대 ID입니다. 예: Asia/Seoul",
                                    false
                            )
                    )
            );

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String zoneIdValue =
                getZoneIdValue(
                        arguments
                );

        try {
            ZoneId zoneId =
                    ZoneId.of(
                            zoneIdValue
                    );

            ZonedDateTime currentTime =
                    ZonedDateTime.now(
                            zoneId
                    );

            return ToolResult.success(
                    currentTime.format(
                            FORMATTER
                    )
            );
        } catch (DateTimeException exception) {
            return ToolResult.failure(
                    "유효하지 않은 시간대입니다: "
                            + zoneIdValue
            );
        }
    }

    private String getZoneIdValue(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return DEFAULT_ZONE_ID;
        }

        Object zoneId =
                arguments.get(
                        "zoneId"
                );

        if (zoneId == null) {
            return DEFAULT_ZONE_ID;
        }

        String value =
                zoneId.toString()
                        .trim();

        return value.isBlank()
                ? DEFAULT_ZONE_ID
                : value;
    }
}