package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.domain.video.service.VideoShortsGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoShortsGenerateTool implements AgentTool {

    private static final long DEFAULT_DURATION_SECONDS =
            45L;

    private static final long DEFAULT_COUNT =
            1L;

    private static final long MAX_COUNT =
            10L;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "video_shorts_generate",
                    "첨부된 영상의 자막과 화면 분석 결과를 바탕으로 "
                            + "짧은 세로형 하이라이트 영상을 하나 이상 생성합니다. "
                            + "사용자가 여러 개의 쇼츠를 요청한 경우 반드시 count에 요청한 개수를 지정하고 "
                            + "이 도구를 한 번만 호출합니다.",
                    Map.of(
                            "fileId",
                            new ToolParameter(
                                    "string",
                                    "쇼츠 영상을 생성할 첨부 영상의 fileId입니다. "
                                            + "현재 첨부 파일 정보에 제공된 fileId를 그대로 사용합니다.",
                                    true
                            ),
                            "durationSeconds",
                            new ToolParameter(
                                    "number",
                                    "각 쇼츠 영상의 목표 길이(초)입니다. "
                                            + "사용자가 길이를 지정하지 않으면 45초를 사용합니다.",
                                    false
                            ),
                            "count",
                            new ToolParameter(
                                    "number",
                                    "생성할 쇼츠 영상의 개수입니다. "
                                            + "사용자가 개수를 지정하지 않으면 1개를 생성합니다. "
                                            + "여러 개를 요청한 경우 도구를 반복 호출하지 말고 "
                                            + "이 값에 요청한 개수를 지정합니다.",
                                    false
                            )
                    )
            );

    private final VideoShortsGenerator videoShortsGenerator;

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
                    "쇼츠로 만들 영상의 fileId가 없습니다."
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
                    "쇼츠 영상 길이는 0초보다 커야 합니다."
            );
        }

        long count =
                getLongArgument(
                        arguments,
                        "count",
                        DEFAULT_COUNT
                );

        if (
                count <= 0
                        || count > MAX_COUNT
        ) {
            return ToolResult.failure(
                    "쇼츠 생성 개수는 1개 이상 "
                            + MAX_COUNT
                            + "개 이하여야 합니다."
            );
        }

        try {
            long targetDurationMillis =
                    Math.multiplyExact(
                            durationSeconds,
                            1_000L
                    );

            int targetCount =
                    Math.toIntExact(
                            count
                    );

            log.info(
                    "Video Shorts Generate Tool 실행 시작. fileId={}, durationSeconds={}, count={}",
                    fileId,
                    durationSeconds,
                    targetCount
            );

            List<Path> outputPaths =
                    videoShortsGenerator.generate(
                            fileId,
                            targetDurationMillis,
                            targetCount
                    );

            log.info(
                    "Video Shorts Generate Tool 실행 완료. fileId={}, durationSeconds={}, count={}, outputPaths={}",
                    fileId,
                    durationSeconds,
                    outputPaths.size(),
                    outputPaths
            );

            List<String> generatedFileNames =
                    outputPaths.stream()
                            .map(
                                    outputPath ->
                                            outputPath
                                                    .getFileName()
                                                    .toString()
                            )
                            .toList();

            String generatedFiles =
                    generatedFileNames.stream()
                            .collect(
                                    Collectors.joining(
                                            System.lineSeparator()
                                    )
                            );

            return ToolResult.success(
                    "쇼츠 영상 생성이 완료되었습니다."
                            + System.lineSeparator()
                            + "목표 길이: "
                            + durationSeconds
                            + "초"
                            + System.lineSeparator()
                            + "생성 개수: "
                            + outputPaths.size()
                            + "개"
                            + System.lineSeparator()
                            + "생성 파일:"
                            + System.lineSeparator()
                            + generatedFiles,
                    Map.of(
                            "generatedFiles",
                            generatedFileNames
                    )
            );
        } catch (ArithmeticException exception) {
            return ToolResult.failure(
                    "쇼츠 영상 길이 또는 생성 개수 값이 너무 큽니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Video Shorts Generate Tool 실행 실패. fileId={}, durationSeconds={}, count={}",
                    fileId,
                    durationSeconds,
                    count,
                    exception
            );

            return ToolResult.failure(
                    "쇼츠 영상을 생성하는 중 오류가 발생했습니다: "
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