package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.domain.video.service.VideoSummaryGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoSummaryGenerateTool implements AgentTool {

    private static final long DEFAULT_DURATION_SECONDS =
            180L;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "video_summary_generate",
                    "첨부된 영상의 자막과 화면 분석 결과를 바탕으로 "
                            + "중요한 장면을 선정하고 실제 MP4 요약 영상을 생성합니다. "
                            + "사용자가 영상 요약본, 하이라이트 영상, 짧은 편집본 생성을 요청할 때 사용합니다.",
                    Map.of(
                            "fileId",
                            new ToolParameter(
                                    "string",
                                    "요약 영상을 생성할 첨부 영상의 fileId입니다. "
                                            + "현재 첨부 파일 정보에 제공된 fileId를 사용합니다.",
                                    true
                            ),
                            "durationSeconds",
                            new ToolParameter(
                                    "number",
                                    "생성할 요약 영상의 목표 길이(초)입니다. "
                                            + "사용자가 길이를 지정하지 않으면 180초를 사용합니다.",
                                    false
                            )
                    )
            );

    private final VideoSummaryGenerator videoSummaryGenerator;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String fileId =
                getStringArgument(
                        arguments,
                        "fileId"
                );

        if (fileId == null) {
            return ToolResult.failure(
                    "요약할 영상의 fileId가 없습니다."
            );
        }

        long durationSeconds =
                getLongArgument(
                        arguments,
                        "durationSeconds",
                        DEFAULT_DURATION_SECONDS
                );

        if (durationSeconds <= 0) {
            return ToolResult.failure(
                    "요약 영상 길이는 0초보다 커야 합니다."
            );
        }

        try {
            long targetDurationMillis =
                    Math.multiplyExact(
                            durationSeconds,
                            1_000L
                    );

            log.info(
                    "Video Summary Generate Tool 실행 시작. fileId={}, durationSeconds={}",
                    fileId,
                    durationSeconds
            );

            Path outputPath =
                    videoSummaryGenerator.generate(
                            fileId,
                            targetDurationMillis
                    );

            log.info(
                    "Video Summary Generate Tool 실행 완료. fileId={}, durationSeconds={}, outputPath={}",
                    fileId,
                    durationSeconds,
                    outputPath
            );

            return ToolResult.success(
                    "요약 영상 생성이 완료되었습니다."
                            + System.lineSeparator()
                            + "목표 길이: "
                            + durationSeconds
                            + "초"
                            + System.lineSeparator()
                            + "생성된 영상은 사용자 화면의 영상 요약 카드에서 "
                            + "재생하거나 다운로드할 수 있습니다."
            );
        } catch (ArithmeticException exception) {
            return ToolResult.failure(
                    "요약 영상 길이 값이 너무 큽니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Video Summary Generate Tool 실행 실패. fileId={}, durationSeconds={}",
                    fileId,
                    durationSeconds,
                    exception
            );

            return ToolResult.failure(
                    "요약 영상을 생성하는 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }
    }

    private String getStringArgument(
            Map<String, Object> arguments,
            String name
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        name
                );

        if (value == null) {
            return null;
        }

        String normalizedValue =
                value.toString()
                        .trim();

        return normalizedValue.isBlank()
                ? null
                : normalizedValue;
    }

    private long getLongArgument(
            Map<String, Object> arguments,
            String name,
            long defaultValue
    ) {
        if (
                arguments == null
                        || arguments.get(name) == null
        ) {
            return defaultValue;
        }

        Object value =
                arguments.get(
                        name
                );

        if (value instanceof Number number) {
            return number.longValue();
        }

        String normalizedValue =
                value.toString()
                        .trim();

        if (normalizedValue.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(
                    normalizedValue
            );
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name
                            + " 값이 숫자가 아닙니다: "
                            + normalizedValue,
                    exception
            );
        }
    }
}