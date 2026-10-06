package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.ChatFileChunk;
import com.agent.aiagent.domain.file.repository.ChatFileChunkRepository;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentCodebaseOverviewTool implements AgentTool {

    private static final String SOURCE_PATH_PREFIX =
            "SOURCE_PATH:";

    private static final String SOURCE_EXTENSION_PREFIX =
            "SOURCE_EXTENSION:";

    private static final int MAX_SELECTED_FILES =
            30;

    /*
     * overview는 원문 코드를 전달하지 않고 구조화된 인덱스만 전달한다.
     *
     * 너무 많은 메서드/annotation 때문에 특정 파일이 결과를 독점하지
     * 않도록 파일별 항목 수도 제한한다.
     */
    private static final int MAX_ANNOTATIONS_PER_FILE =
            12;

    private static final int MAX_METHODS_PER_FILE =
            20;

    private static final int MAX_MAPPINGS_PER_FILE =
            20;

    private static final int MAX_CONFIG_KEYS_PER_FILE =
            20;

    private static final int MAX_FIELDS_PER_FILE =
            15;

    private static final int MAX_IMPORTS_PER_FILE =
            20;

    private static final int MAX_EXPORTS_PER_FILE =
            15;

    private static final int MAX_FRAMEWORK_SIGNALS_PER_FILE =
            20;

    private static final int MAX_PACKAGE_ITEMS =
            20;

    private static final int MAX_INDEX_CONTENT_LENGTH =
            24_000;

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*package\\s+([a-zA-Z_][\\w.]*)\\s*;"
            );

    private static final Pattern TYPE_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+)?"
                            + "(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*"
                            + "(class|interface|enum|record)\\s+"
                            + "([A-Za-z_$][\\w$]*)\\b"
            );

    /*
     * annotation 이름만 뽑는다.
     *
     * annotation argument 전체를 여기서 다시 해석하지 않는다.
     * API mapping은 별도 MAPPING_PATTERN에서 실제 annotation text를 추출한다.
     */
    private static final Pattern ANNOTATION_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*@([A-Za-z_$][\\w$.]*)"
            );

    /*
     * Spring MVC mapping annotation.
     *
     * 여러 줄 argument도 받을 수 있도록 DOTALL을 사용하지만,
     * ')'까지의 실제 annotation text만 추출한다.
     */
    private static final Pattern MAPPING_PATTERN =
            Pattern.compile(
                    "@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)"
                            + "\\s*(\\((?s:.*?)\\))?"
            );

    /*
     * Java 메서드 선언을 완벽한 AST 수준으로 파싱하려는 목적이 아니다.
     *
     * 실제 코드에 존재하는 메서드 signature의 시작 부분만 보수적으로 추출한다.
     * 생성자, lambda, control statement는 아래 필터에서 제외한다.
     */
    private static final Pattern METHOD_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*"
                            + "(?:public|protected|private)\\s+"
                            + "(?:(?:static|final|synchronized|abstract|default|native)\\s+)*"
                            + "(?:<[^>{};]+>\\s+)?"
                            + "([\\w.$<>?,\\[\\]\\s]+?)\\s+"
                            + "([A-Za-z_$][\\w$]*)\\s*"
                            + "\\(([^;{}]*)\\)"
                            + "\\s*(?:throws\\s+[^\\{;]+)?"
                            + "\\s*(?:\\{|;)"
            );

    /*
     * ConfigurationProperties prefix.
     */
    private static final Pattern CONFIGURATION_PROPERTIES_PATTERN =
            Pattern.compile(
                    "@ConfigurationProperties\\s*"
                            + "\\(\\s*"
                            + "(?:prefix\\s*=\\s*)?"
                            + "\"([^\"]+)\""
            );

    /*
     * @Value("${...}") 형태.
     */
    private static final Pattern VALUE_PATTERN =
            Pattern.compile(
                    "@Value\\s*\\(\\s*\"\\$\\{([^}:]+)(?::[^}]*)?}\"\\s*\\)"
            );

    /*
     * application.properties / yaml / yml에서 확실하게 확인되는 설정 key를
     * 가볍게 인덱싱하기 위한 패턴.
     */
    private static final Pattern PROPERTY_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*.+$"
            );

    /*
     * Java field는 클래스 역할을 파악하는 보조 정보다.
     * 초기화식이나 실제 값은 전달하지 않는다.
     */
    private static final Pattern FIELD_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*"
                            + "(?:private|protected|public)\\s+"
                            + "(?:(?:static|final|transient|volatile)\\s+)*"
                            + "([A-Za-z_$][\\w$<>?,.\\[\\]\\s]*)\\s+"
                            + "([A-Za-z_$][\\w$]*)"
                            + "\\s*(?:=|;)"
            );

    private static final Pattern SCRIPT_FUNCTION_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)"
            );

    private static final Pattern SCRIPT_ARROW_FUNCTION_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*(?:export\\s+)?const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\(([^)]*)\\)\\s*=>"
            );

    private static final Pattern SCRIPT_ARROW_SINGLE_PARAM_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*(?:export\\s+)?const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?([A-Za-z_$][\\w$]*)\\s*=>"
            );

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*import\\s+(.+?)\\s+from\\s+['\\\"]([^'\\\"]+)['\\\"]\\s*;?\\s*$"
            );

    private static final Pattern SIDE_EFFECT_IMPORT_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*import\\s+['\\\"]([^'\\\"]+)['\\\"]\\s*;?\\s*$"
            );

    private static final Pattern EXPORT_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?(?:function|class|interface|type|const|let|var|enum)\\s+([A-Za-z_$][\\w$]*)"
            );

    private static final Set<String> EXCLUDED_METHOD_NAMES =
            Set.of(
                    "if",
                    "for",
                    "while",
                    "switch",
                    "catch",
                    "return",
                    "new",
                    "throw",
                    "synchronized"
            );

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "attachment_codebase_overview",
                    """
                    현재 대화에 첨부된 ZIP 프로젝트의 주요 구현 구조를 전체적으로 파악할 때 사용합니다.

                    attachment_project_structure가 전체 파일/디렉터리 구조를 제공한다면,
                    이 도구는 프로젝트 분석에 중요한 파일을 선별하여 각 파일에서 실제로 확인된
                    Java/Spring의 package, class/interface/enum/record, annotation, API mapping, method signature와
                    Vue/React/TypeScript/JavaScript의 import/export, 함수, framework signal, package.json 항목,
                    configuration key 정보를 압축된 코드 인덱스로 제공합니다.

                    프로젝트 전체 분석:
                    attachment_project_structure -> attachment_codebase_overview

                    특정 클래스, 메서드, API 구현, 비즈니스 로직, 메서드 body 등 상세 코드가
                    추가로 필요한 경우:
                    attachment_codebase_overview -> attachment_search

                    이 overview는 구현 body 전체를 제공하지 않습니다.
                    overview에 없는 구현 세부사항을 추측하지 말고 필요한 경우 attachment_search를 사용하세요.

                    프로젝트 디렉터리 구조는 attachment_project_structure 결과를 기준으로 하며,
                    overview의 SOURCE_PATH를 이용해 디렉터리 부모 관계를 새로 추론하지 마세요.

                    이 도구는 현재 대화에 첨부된 ZIP만 분석하며 로컬 작업 폴더를 읽지 않습니다.
                    """.trim(),
                    Map.of()
            );

    private final ChatFileChunkRepository chatFileChunkRepository;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        return ToolResult.failure(
                "현재 대화의 첨부파일 정보가 없습니다."
        );
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments,
            ToolExecutionContext context
    ) {
        if (
                context == null
                        || context.roomId() == null
                        || context.roomId().isBlank()
        ) {
            return ToolResult.failure(
                    "현재 대화 정보를 확인할 수 없습니다."
            );
        }

        if (
                context.fileIds() == null
                        || context.fileIds().isEmpty()
        ) {
            return ToolResult.failure(
                    "현재 대화에 첨부된 파일이 없습니다."
            );
        }

        try {
            List<ChatFileChunk> chunks =
                    chatFileChunkRepository
                            .findAllByRoomIdAndFileIdInOrderByFileIdAscChunkIndexAsc(
                                    context.roomId(),
                                    context.fileIds()
                            );

            Map<String, List<ChatFileChunk>> chunksBySourcePath =
                    groupBySourcePath(
                            chunks
                    );

            if (chunksBySourcePath.isEmpty()) {
                return ToolResult.failure(
                        "첨부파일에서 프로젝트 SOURCE_PATH 정보를 찾지 못했습니다."
                );
            }

            List<String> selectedSourcePaths =
                    selectImportantSourcePaths(
                            chunksBySourcePath
                    );

            if (selectedSourcePaths.isEmpty()) {
                return ToolResult.failure(
                        "프로젝트 분석에 사용할 주요 소스 파일을 찾지 못했습니다."
                );
            }

            CodebaseIndexBuildResult buildResult =
                    buildContent(
                            selectedSourcePaths,
                            chunksBySourcePath
                    );

            log.info(
                    "Attachment Codebase Overview Tool 실행 완료. "
                            + "roomId={}, fileCount={}, totalSourcePathCount={}, "
                            + "selectedSourcePathCount={}, indexedFileCount={}, "
                            + "contentLength={}, contentLimitReached={}",
                    context.roomId(),
                    context.fileIds().size(),
                    chunksBySourcePath.size(),
                    selectedSourcePaths.size(),
                    buildResult.indexedFileCount(),
                    buildResult.content().length(),
                    buildResult.contentLimitReached()
            );

            return ToolResult.success(
                    buildResult.content(),
                    Map.of(
                            "totalSourcePathCount",
                            chunksBySourcePath.size(),
                            "selectedSourcePathCount",
                            selectedSourcePaths.size(),
                            "indexedFileCount",
                            buildResult.indexedFileCount(),
                            "contentLength",
                            buildResult.content().length(),
                            "contentLimitReached",
                            buildResult.contentLimitReached()
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "Attachment Codebase Overview Tool 실행 실패. "
                            + "roomId={}, fileIds={}",
                    context.roomId(),
                    context.fileIds(),
                    exception
            );

            return ToolResult.failure(
                    "첨부 프로젝트의 주요 구현을 분석하는 중 오류가 발생했습니다."
            );
        }
    }

    private Map<String, List<ChatFileChunk>> groupBySourcePath(
            List<ChatFileChunk> chunks
    ) {
        Map<String, List<ChatFileChunk>> chunksBySourcePath =
                new LinkedHashMap<>();

        for (ChatFileChunk chunk : chunks) {
            String sourcePath =
                    extractSourcePath(
                            chunk.getContent()
                    );

            if (sourcePath == null) {
                continue;
            }

            chunksBySourcePath
                    .computeIfAbsent(
                            sourcePath,
                            key ->
                                    new ArrayList<>()
                    )
                    .add(
                            chunk
                    );
        }

        return chunksBySourcePath;
    }

    private String extractSourcePath(
            String content
    ) {
        if (
                content == null
                        || content.isBlank()
        ) {
            return null;
        }

        String[] lines =
                content.split(
                        "\\R",
                        6
                );

        for (String line : lines) {
            String trimmed =
                    line.trim();

            if (!trimmed.startsWith(SOURCE_PATH_PREFIX)) {
                continue;
            }

            String sourcePath =
                    trimmed.substring(
                                    SOURCE_PATH_PREFIX.length()
                            )
                            .trim()
                            .replace(
                                    '\\',
                                    '/'
                            );

            return sourcePath.isBlank()
                    ? null
                    : sourcePath;
        }

        return null;
    }

    private List<String> selectImportantSourcePaths(
            Map<String, List<ChatFileChunk>> chunksBySourcePath
    ) {
        List<SourceCandidate> candidates =
                new ArrayList<>();

        for (String sourcePath : chunksBySourcePath.keySet()) {
            int score =
                    calculateImportanceScore(
                            sourcePath
                    );

            if (score <= 0) {
                continue;
            }

            candidates.add(
                    new SourceCandidate(
                            sourcePath,
                            score
                    )
            );
        }

        candidates.sort(
                this::compareCandidates
        );

        LinkedHashSet<String> selectedSourcePaths =
                new LinkedHashSet<>();

        /*
         * 특정 프로젝트명이나 특정 도메인 경로에 의존하지 않고
         * 일반적인 full-stack 프로젝트 역할별로 overview를 고르게 구성한다.
         */
        selectByCategory(candidates, selectedSourcePaths, this::isProjectCoreFile, 6);

        // Backend / server-side
        selectByCategory(candidates, selectedSourcePaths, this::isBackendApiFile, 4);
        selectByCategory(candidates, selectedSourcePaths, this::isBackendServiceFile, 4);
        selectByCategory(candidates, selectedSourcePaths, this::isBackendDataFile, 3);
        selectByCategory(candidates, selectedSourcePaths, this::isBackendModelFile, 3);
        selectByCategory(candidates, selectedSourcePaths, this::isConfigurationFile, 3);

        // Frontend / client-side
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendEntryFile, 3);
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendRouterFile, 2);
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendStoreFile, 2);
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendApiFile, 3);
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendComposableOrHookFile, 2);
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendPageFile, 3);
        selectByCategory(candidates, selectedSourcePaths, this::isFrontendComponentFile, 3);

        // 남은 슬롯은 framework/language에 관계없이 importance score 순으로 채운다.
        for (SourceCandidate candidate : candidates) {
            if (selectedSourcePaths.size() >= MAX_SELECTED_FILES) {
                break;
            }

            selectedSourcePaths.add(
                    candidate.sourcePath()
            );
        }

        return selectedSourcePaths.stream()
                .limit(MAX_SELECTED_FILES)
                .toList();
    }

    private int compareCandidates(
            SourceCandidate left,
            SourceCandidate right
    ) {
        int scoreCompare =
                Integer.compare(
                        right.score(),
                        left.score()
                );

        if (scoreCompare != 0) {
            return scoreCompare;
        }

        return left.sourcePath()
                .compareTo(
                        right.sourcePath()
                );
    }

    private void selectByCategory(
            List<SourceCandidate> candidates,
            LinkedHashSet<String> selectedSourcePaths,
            Predicate<String> matcher,
            int limit
    ) {
        if (selectedSourcePaths.size() >= MAX_SELECTED_FILES) {
            return;
        }

        int selectedCount =
                0;

        for (SourceCandidate candidate : candidates) {
            if (
                    selectedSourcePaths.size() >= MAX_SELECTED_FILES
                            || selectedCount >= limit
            ) {
                break;
            }

            String sourcePath =
                    candidate.sourcePath();

            if (
                    selectedSourcePaths.contains(
                            sourcePath
                    )
                            || !matcher.test(
                            sourcePath
                    )
            ) {
                continue;
            }

            selectedSourcePaths.add(
                    sourcePath
            );

            selectedCount++;
        }
    }

    private boolean isProjectCoreFile(
            String sourcePath
    ) {
        String normalized =
                sourcePath.toLowerCase(
                        Locale.ROOT
                );

        String fileName =
                getFileName(
                        normalized
                );

        return fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")
                || fileName.equals("pom.xml")
                || fileName.equals("settings.gradle")
                || fileName.equals("settings.gradle.kts")
                || fileName.equals("application.yml")
                || fileName.equals("application.yaml")
                || fileName.equals("application.properties")
                || fileName.equals("docker-compose.yml")
                || fileName.equals("docker-compose.yaml")
                || fileName.startsWith("readme")
                || fileName.endsWith("application.java")
                || fileName.endsWith("application.kt");
    }

    private boolean isRagRelatedFile(
            String sourcePath
    ) {
        String normalized =
                sourcePath.toLowerCase(
                        Locale.ROOT
                );

        String fileName =
                getFileName(
                        normalized
                );

        return normalized.contains("/rag/")
                || fileName.contains("rag");
    }

    private boolean isVideoRelatedFile(
            String sourcePath
    ) {
        String normalized =
                sourcePath.toLowerCase(
                        Locale.ROOT
                );

        String fileName =
                getFileName(
                        normalized
                );

        return normalized.contains("/video/")
                || fileName.contains("video");
    }

    private boolean isCodeEditRelatedFile(
            String sourcePath
    ) {
        String normalized =
                sourcePath.toLowerCase(
                        Locale.ROOT
                );

        String fileName =
                getFileName(
                        normalized
                );

        return normalized.contains("/codeedit/")
                || normalized.contains("/code-edit/")
                || fileName.contains("codeedit")
                || fileName.contains("editcode");
    }

    private boolean isBackendApiFile(
            String sourcePath
    ) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);

        return normalized.contains("/controller/")
                || normalized.contains("/controllers/")
                || normalized.contains("/api/")
                || normalized.contains("/resource/")
                || normalized.contains("/resources/")
                || fileName.contains("controller")
                || fileName.contains("resource");
    }

    private boolean isBackendServiceFile(
            String sourcePath
    ) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);

        return normalized.contains("/service/")
                || normalized.contains("/services/")
                || normalized.contains("/usecase/")
                || normalized.contains("/use-case/")
                || fileName.contains("service")
                || fileName.contains("usecase");
    }

    private boolean isBackendDataFile(
            String sourcePath
    ) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);

        return normalized.contains("/repository/")
                || normalized.contains("/repositories/")
                || normalized.contains("/mapper/")
                || normalized.contains("/dao/")
                || normalized.contains("/persistence/")
                || fileName.contains("repository")
                || fileName.contains("mapper")
                || fileName.contains("dao");
    }

    private boolean isBackendModelFile(
            String sourcePath
    ) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);

        return normalized.contains("/entity/")
                || normalized.contains("/entities/")
                || normalized.contains("/model/")
                || normalized.contains("/models/")
                || normalized.contains("/dto/")
                || normalized.contains("/domain/");
    }

    private boolean isConfigurationFile(
            String sourcePath
    ) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);

        return normalized.contains("/config/")
                || normalized.contains("/configuration/")
                || normalized.contains("/settings/")
                || normalized.contains("/infra/")
                || fileName.startsWith("application.")
                || fileName.startsWith("docker-compose.")
                || fileName.startsWith("vite.config.")
                || fileName.startsWith("nuxt.config.")
                || fileName.startsWith("next.config.");
    }

    private boolean isFrontendEntryFile(
            String sourcePath
    ) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);

        return fileName.equals("package.json")
                || fileName.equals("vite.config.ts")
                || fileName.equals("vite.config.js")
                || fileName.equals("nuxt.config.ts")
                || fileName.equals("next.config.js")
                || fileName.equals("next.config.mjs")
                || fileName.equals("app.vue")
                || fileName.equals("main.ts")
                || fileName.equals("main.js")
                || fileName.equals("app.tsx")
                || fileName.equals("app.jsx");
    }

    private boolean isFrontendRouterFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);
        return normalized.contains("/router/")
                || normalized.contains("/routes/")
                || fileName.equals("router.ts")
                || fileName.equals("router.js")
                || fileName.equals("routes.ts")
                || fileName.equals("routes.js");
    }

    private boolean isFrontendStoreFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("/store/")
                || normalized.contains("/stores/")
                || normalized.contains("/vuex/")
                || normalized.contains("/pinia/");
    }

    private boolean isFrontendApiFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("/api/")
                || normalized.contains("/apis/")
                || normalized.contains("/services/api/")
                || normalized.contains("/client/");
    }

    private boolean isFrontendComposableOrHookFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        String fileName = getFileName(normalized);
        return normalized.contains("/composables/")
                || normalized.contains("/hooks/")
                || (fileName.startsWith("use")
                && (fileName.endsWith(".ts")
                || fileName.endsWith(".js")
                || fileName.endsWith(".tsx")
                || fileName.endsWith(".jsx")));
    }

    private boolean isFrontendPageFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("/pages/")
                || normalized.contains("/views/")
                || normalized.contains("/screens/");
    }

    private boolean isFrontendComponentFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("/components/")
                || normalized.contains("/component/");
    }

    private boolean isFrontendSourceFile(String sourcePath) {
        String normalized = sourcePath.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".vue")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx")
                || normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".json")
                || normalized.endsWith(".html")
                || normalized.endsWith(".css")
                || normalized.endsWith(".scss")
                || normalized.endsWith(".sass")
                || normalized.endsWith(".less");
    }

    private boolean containsPathSegment(
            String sourcePath,
            String segment
    ) {
        return sourcePath.toLowerCase(
                Locale.ROOT
        ).contains(
                segment
        );
    }

    private String getFileName(
            String sourcePath
    ) {
        int separatorIndex =
                sourcePath.lastIndexOf('/');

        if (separatorIndex < 0) {
            return sourcePath;
        }

        return sourcePath.substring(
                separatorIndex + 1
        );
    }

    private int calculateImportanceScore(
            String sourcePath
    ) {
        String normalized =
                sourcePath.toLowerCase(
                        Locale.ROOT
                );

        String fileName =
                getFileName(
                        normalized
                );

        int score =
                0;

        if (
                fileName.equals("build.gradle")
                        || fileName.equals("build.gradle.kts")
                        || fileName.equals("pom.xml")
        ) {
            score += 100;
        }

        if (
                fileName.equals("settings.gradle")
                        || fileName.equals("settings.gradle.kts")
        ) {
            score += 95;
        }

        if (
                fileName.equals("application.yml")
                        || fileName.equals("application.yaml")
                        || fileName.equals("application.properties")
        ) {
            score += 90;
        }

        if (
                fileName.equals("docker-compose.yml")
                        || fileName.equals("docker-compose.yaml")
        ) {
            score += 90;
        }

        if (fileName.startsWith("readme")) {
            score += 85;
        }

        if (
                fileName.endsWith("application.java")
                        || fileName.endsWith("application.kt")
        ) {
            score += 85;
        }

        if (fileName.endsWith("controller.java")) {
            score += 80;
        }

        if (fileName.endsWith("service.java")) {
            score += 75;
        }

        if (fileName.endsWith("repository.java")) {
            score += 70;
        }

        if (fileName.endsWith("entity.java")) {
            score += 65;
        }

        if (fileName.endsWith("tool.java")) {
            score += 75;
        }

        if (fileName.endsWith("provider.java")) {
            score += 70;
        }

        if (
                fileName.endsWith("config.java")
                        || fileName.endsWith("configuration.java")
        ) {
            score += 65;
        }

        if (
                normalized.contains("/controller/")
                        || normalized.contains("/api/")
        ) {
            score += 35;
        }

        if (normalized.contains("/service/")) {
            score += 30;
        }

        if (normalized.contains("/repository/")) {
            score += 25;
        }

        if (
                normalized.contains("/entity/")
                        || normalized.contains("/model/")
        ) {
            score += 20;
        }

        if (normalized.contains("/tool/")) {
            score += 35;
        }

        if (normalized.contains("/provider/")) {
            score += 30;
        }

        if (normalized.contains("/infra/")) {
            score += 25;
        }

        if (normalized.contains("/config/")) {
            score += 25;
        }

        if (fileName.equals("package.json")) {
            score += 100;
        }

        if (fileName.equals("vite.config.ts") || fileName.equals("vite.config.js")
                || fileName.equals("nuxt.config.ts")
                || fileName.equals("next.config.js")
                || fileName.equals("next.config.mjs")) {
            score += 90;
        }

        if (fileName.equals("app.vue") || fileName.equals("main.ts")
                || fileName.equals("main.js") || fileName.equals("app.tsx")
                || fileName.equals("app.jsx")) {
            score += 85;
        }

        if (isFrontendRouterFile(sourcePath)) {
            score += 70;
        }

        if (isFrontendStoreFile(sourcePath)) {
            score += 65;
        }

        if (isFrontendApiFile(sourcePath)) {
            score += 65;
        }

        if (isFrontendComposableOrHookFile(sourcePath)) {
            score += 55;
        }

        if (isFrontendPageFile(sourcePath)) {
            score += 50;
        }

        if (isFrontendComponentFile(sourcePath)) {
            score += 35;
        }

        // Vue/React/TS/JS 파일은 이름이 특별하지 않아도 후보에서 탈락시키지 않는다.
        // 이 baseline이 기존 140개 중 7개만 후보가 되던 문제를 막는다.
        if (isFrontendSourceFile(sourcePath)) {
            score += 5;
        }


        return score;
    }

    /*
     * ------------------------------------------------------------------
     * Codebase overview index
     * ------------------------------------------------------------------
     *
     * 기존 방식:
     *   선택된 파일의 chunk 원문을 최대 30,000자까지 그대로 LLM에 전달.
     *
     * 문제:
     *   30개를 선택해도 앞쪽 14개 정도에서 길이 제한에 도달.
     *
     * 현재 방식:
     *   파일의 모든 chunk를 서버 내부에서 합친 뒤 구조 정보만 추출한다.
     *
     *   SOURCE_PATH
     *   PACKAGE
     *   TYPE
     *   CLASS
     *   ANNOTATIONS
     *   MAPPINGS
     *   METHODS
     *   FIELDS
     *   CONFIG_KEYS
     *
     * 메서드 body는 전달하지 않는다.
     */
    private CodebaseIndexBuildResult buildContent(
            List<String> selectedSourcePaths,
            Map<String, List<ChatFileChunk>> chunksBySourcePath
    ) {
        StringBuilder content =
                new StringBuilder();

        content.append(
                "첨부 ZIP 프로젝트 주요 코드 인덱스\n\n"
        );

        content.append(
                        "전체 SOURCE_PATH 수: "
                )
                .append(
                        chunksBySourcePath.size()
                )
                .append(
                        "\n"
                );

        content.append(
                        "선별된 주요 파일 수: "
                )
                .append(
                        selectedSourcePaths.size()
                )
                .append(
                        "\n\n"
                );

        content.append(
                """
                아래 정보는 첨부 ZIP의 실제 저장 chunk를 파일별로 결합한 뒤
                코드에서 직접 확인된 구조 정보만 추출한 결과입니다.
                메서드 body와 상세 비즈니스 로직은 포함하지 않습니다.

                """
        );

        int indexedFileCount =
                0;

        boolean contentLimitReached =
                false;

        for (String sourcePath : selectedSourcePaths) {
            List<ChatFileChunk> fileChunks =
                    chunksBySourcePath.get(
                            sourcePath
                    );

            if (
                    fileChunks == null
                            || fileChunks.isEmpty()
            ) {
                continue;
            }

            String source =
                    mergeSourceChunks(
                            fileChunks
                    );

            if (source.isBlank()) {
                continue;
            }

            SourceIndex sourceIndex =
                    createSourceIndex(
                            sourcePath,
                            source
                    );

            String indexText =
                    formatSourceIndex(
                            sourceIndex
                    );

            if (
                    content.length()
                            + indexText.length()
                            > MAX_INDEX_CONTENT_LENGTH
            ) {
                contentLimitReached =
                        true;

                break;
            }

            content.append(
                    indexText
            );

            indexedFileCount++;
        }

        if (contentLimitReached) {
            content.append(
                    """
                    
                    [INDEX OUTPUT LIMIT]
                    코드 인덱스 출력 길이 제한으로 일부 선별 파일의 인덱스가 생략되었습니다.
                    생략된 파일의 세부 정보가 필요하면 attachment_search를 사용하세요.
                    """
            );
        }

        content.append(
                """

                [최종 응답 작성 규칙]
                - 프로젝트 디렉터리 구조와 파일 위치는 attachment_project_structure 결과만 기준으로 설명하세요.
                - 디렉터리 트리를 출력할 경우 attachment_project_structure 결과의 실제 부모-자식 관계를 유지하세요.
                - attachment_project_structure 결과에 없는 부모-자식 디렉터리 관계를 새로 만들지 마세요.
                - 출력 길이를 줄이기 위해 구조를 요약하더라도 실제 부모 경로를 변경하지 마세요.
                - 이 overview의 SOURCE_PATH를 이용해 프로젝트 디렉터리 구조를 새로 추론하거나 재구성하지 마세요.
                - 이 overview는 코드 구조 인덱스이며 메서드 body 전체를 제공하지 않습니다.
                - PACKAGE, TYPE, CLASS, ANNOTATIONS, MAPPINGS, METHODS, SCRIPT_FUNCTIONS, FIELDS, IMPORTS, EXPORTS, FRAMEWORK_SIGNALS, CONFIG_KEYS, PACKAGE_ITEMS는 실제 chunk에서 추출된 정보만 사용하세요.
                - API 경로와 HTTP Method는 MAPPINGS에 실제 mapping annotation이 확인된 경우에만 설명하세요.
                - MAPPINGS에 없는 API 경로나 HTTP Method를 클래스명, 메서드명 또는 Spring 관례로 추측하지 마세요.
                - 설정 prefix 또는 key는 CONFIG_KEYS에 실제로 확인된 값만 사용하세요.
                - 메서드의 내부 동작과 비즈니스 로직은 METHODS의 signature만 보고 추측하지 마세요.
                - 메서드 body 또는 구체적인 구현이 필요한 경우 attachment_search를 사용하세요.
                - 특정 클래스가 이 overview에 없다고 해서 프로젝트에 존재하지 않는다고 단정하지 마세요.
                - '모든 클래스', '모든 API', '전체 구현을 확인했다'와 같은 표현은 실제 전체 소스를 확인한 경우가 아니면 사용하지 마세요.
                - 확인되지 않은 내용은 일반적인 프레임워크 지식으로 채우지 마세요.
                - 첨부 ZIP에 없는 로컬 작업 폴더의 코드나 모델의 기존 지식을 현재 프로젝트 구현처럼 혼합하지 마세요.
                """
        );

        return new CodebaseIndexBuildResult(
                content.toString()
                        .trim(),
                indexedFileCount,
                contentLimitReached
        );
    }

    /*
     * 한 SOURCE_PATH의 모든 chunk를 합친다.
     *
     * 여기서 합쳐진 source는 LLM으로 그대로 전달되지 않는다.
     * 구조 정보 추출에만 사용한다.
     */
    private String mergeSourceChunks(
            List<ChatFileChunk> chunks
    ) {
        StringBuilder source =
                new StringBuilder();

        for (ChatFileChunk chunk : chunks) {
            String content =
                    chunk.getContent();

            if (
                    content == null
                            || content.isBlank()
            ) {
                continue;
            }

            String cleaned =
                    removeChunkMetadata(
                            content
                    );

            if (cleaned.isBlank()) {
                continue;
            }

            if (!source.isEmpty()) {
                source.append(
                        "\n"
                );
            }

            source.append(
                    cleaned
            );
        }

        return source.toString();
    }

    /*
     * 각 chunk 앞에 붙어 있는
     *
     * SOURCE_PATH:
     * SOURCE_EXTENSION:
     *
     * 메타 정보만 제거한다.
     */
    private String removeChunkMetadata(
            String content
    ) {
        StringBuilder cleaned =
                new StringBuilder();

        String[] lines =
                content.split(
                        "\\R",
                        -1
                );

        boolean metadataArea =
                true;

        for (String line : lines) {
            String trimmed =
                    line.trim();

            if (metadataArea) {
                if (
                        trimmed.startsWith(
                                SOURCE_PATH_PREFIX
                        )
                                || trimmed.startsWith(
                                SOURCE_EXTENSION_PREFIX
                        )
                ) {
                    continue;
                }

                if (trimmed.isBlank()) {
                    continue;
                }

                metadataArea =
                        false;
            }

            cleaned.append(
                            line
                    )
                    .append(
                            '\n'
                    );
        }

        return cleaned.toString()
                .trim();
    }

    private SourceIndex createSourceIndex(
            String sourcePath,
            String source
    ) {
        String packageName =
                extractPackageName(
                        source
                );

        TypeInfo typeInfo =
                extractTypeInfo(
                        source
                );

        List<String> annotations =
                extractAnnotations(
                        source
                );

        List<String> mappings =
                extractMappings(
                        source
                );

        List<String> methods =
                extractMethods(
                        source,
                        typeInfo.name()
                );

        List<String> fields =
                extractFields(
                        source
                );

        List<String> configKeys =
                extractConfigKeys(
                        sourcePath,
                        source
                );

        List<String> scriptFunctions =
                extractScriptFunctions(
                        source
                );

        List<String> imports =
                extractImports(
                        source
                );

        List<String> exports =
                extractExports(
                        source
                );

        List<String> frameworkSignals =
                extractFrameworkSignals(
                        sourcePath,
                        source
                );

        List<String> packageItems =
                extractPackageItems(
                        sourcePath,
                        source
                );

        return new SourceIndex(
                sourcePath,
                packageName,
                typeInfo.type(),
                typeInfo.name(),
                annotations,
                mappings,
                methods,
                scriptFunctions,
                fields,
                imports,
                exports,
                frameworkSignals,
                configKeys,
                packageItems
        );
    }

    private String extractPackageName(
            String source
    ) {
        Matcher matcher =
                PACKAGE_PATTERN.matcher(
                        source
                );

        if (!matcher.find()) {
            return null;
        }

        return matcher.group(
                1
        );
    }

    private TypeInfo extractTypeInfo(
            String source
    ) {
        Matcher matcher =
                TYPE_PATTERN.matcher(
                        source
                );

        if (!matcher.find()) {
            return new TypeInfo(
                    null,
                    null
            );
        }

        return new TypeInfo(
                matcher.group(
                        1
                ),
                matcher.group(
                        2
                )
        );
    }

    private List<String> extractAnnotations(
            String source
    ) {
        LinkedHashSet<String> annotations =
                new LinkedHashSet<>();

        Matcher matcher =
                ANNOTATION_PATTERN.matcher(
                        source
                );

        while (
                matcher.find()
                        && annotations.size() < MAX_ANNOTATIONS_PER_FILE
        ) {
            annotations.add(
                    "@"
                            + matcher.group(
                            1
                    )
            );
        }

        return List.copyOf(
                annotations
        );
    }

    private List<String> extractMappings(
            String source
    ) {
        LinkedHashSet<String> mappings =
                new LinkedHashSet<>();

        Matcher matcher =
                MAPPING_PATTERN.matcher(
                        source
                );

        while (
                matcher.find()
                        && mappings.size() < MAX_MAPPINGS_PER_FILE
        ) {
            String annotationName =
                    matcher.group(
                            1
                    );

            String arguments =
                    matcher.group(
                            2
                    );

            String mapping =
                    "@"
                            + annotationName;

            if (
                    arguments != null
                            && !arguments.isBlank()
            ) {
                mapping +=
                        normalizeWhitespace(
                                arguments
                        );
            }

            mappings.add(
                    mapping
            );
        }

        return List.copyOf(
                mappings
        );
    }

    private List<String> extractMethods(
            String source,
            String className
    ) {
        LinkedHashSet<String> methods =
                new LinkedHashSet<>();

        Matcher matcher =
                METHOD_PATTERN.matcher(
                        source
                );

        while (
                matcher.find()
                        && methods.size() < MAX_METHODS_PER_FILE
        ) {
            String returnType =
                    normalizeWhitespace(
                            matcher.group(
                                    1
                            )
                    );

            String methodName =
                    matcher.group(
                            2
                    );

            String parameters =
                    normalizeWhitespace(
                            matcher.group(
                                    3
                            )
                    );

            if (
                    EXCLUDED_METHOD_NAMES.contains(
                            methodName
                    )
            ) {
                continue;
            }

            /*
             * constructor는 별도의 return type이 없으므로 이 정규식에
             * 일반적으로 잡히지 않지만, 잘못 잡히는 경우를 한 번 더 제외한다.
             */
            if (
                    className != null
                            && className.equals(
                            methodName
                    )
            ) {
                continue;
            }

            methods.add(
                    returnType
                            + " "
                            + methodName
                            + "("
                            + parameters
                            + ")"
            );
        }

        return List.copyOf(
                methods
        );
    }

    private List<String> extractFields(
            String source
    ) {
        LinkedHashSet<String> fields =
                new LinkedHashSet<>();

        Matcher matcher =
                FIELD_PATTERN.matcher(
                        source
                );

        while (
                matcher.find()
                        && fields.size() < MAX_FIELDS_PER_FILE
        ) {
            String fieldType =
                    normalizeWhitespace(
                            matcher.group(
                                    1
                            )
                    );

            String fieldName =
                    matcher.group(
                            2
                    );

            fields.add(
                    fieldType
                            + " "
                            + fieldName
            );
        }

        return List.copyOf(
                fields
        );
    }

    private List<String> extractConfigKeys(
            String sourcePath,
            String source
    ) {
        LinkedHashSet<String> configKeys =
                new LinkedHashSet<>();

        Matcher configurationPropertiesMatcher =
                CONFIGURATION_PROPERTIES_PATTERN.matcher(
                        source
                );

        while (
                configurationPropertiesMatcher.find()
                        && configKeys.size() < MAX_CONFIG_KEYS_PER_FILE
        ) {
            configKeys.add(
                    configurationPropertiesMatcher.group(
                            1
                    )
            );
        }

        Matcher valueMatcher =
                VALUE_PATTERN.matcher(
                        source
                );

        while (
                valueMatcher.find()
                        && configKeys.size() < MAX_CONFIG_KEYS_PER_FILE
        ) {
            configKeys.add(
                    valueMatcher.group(
                            1
                    )
            );
        }

        String normalizedPath =
                sourcePath.toLowerCase(
                        Locale.ROOT
                );

        if (
                normalizedPath.endsWith(".properties")
                        && configKeys.size() < MAX_CONFIG_KEYS_PER_FILE
        ) {
            Matcher propertyMatcher =
                    PROPERTY_PATTERN.matcher(
                            source
                    );

            while (
                    propertyMatcher.find()
                            && configKeys.size() < MAX_CONFIG_KEYS_PER_FILE
            ) {
                configKeys.add(
                        propertyMatcher.group(
                                1
                        )
                );
            }
        }

        return List.copyOf(
                configKeys
        );
    }

    private List<String> extractScriptFunctions(
            String source
    ) {
        LinkedHashSet<String> functions = new LinkedHashSet<>();

        Matcher functionMatcher = SCRIPT_FUNCTION_PATTERN.matcher(source);
        while (functionMatcher.find() && functions.size() < MAX_METHODS_PER_FILE) {
            functions.add(
                    functionMatcher.group(1)
                            + "("
                            + normalizeWhitespace(functionMatcher.group(2))
                            + ")"
            );
        }

        Matcher arrowMatcher = SCRIPT_ARROW_FUNCTION_PATTERN.matcher(source);
        while (arrowMatcher.find() && functions.size() < MAX_METHODS_PER_FILE) {
            functions.add(
                    arrowMatcher.group(1)
                            + "("
                            + normalizeWhitespace(arrowMatcher.group(2))
                            + ")"
            );
        }

        Matcher singleParamMatcher = SCRIPT_ARROW_SINGLE_PARAM_PATTERN.matcher(source);
        while (singleParamMatcher.find() && functions.size() < MAX_METHODS_PER_FILE) {
            functions.add(
                    singleParamMatcher.group(1)
                            + "("
                            + singleParamMatcher.group(2)
                            + ")"
            );
        }

        return List.copyOf(functions);
    }

    private List<String> extractImports(
            String source
    ) {
        LinkedHashSet<String> imports = new LinkedHashSet<>();

        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find() && imports.size() < MAX_IMPORTS_PER_FILE) {
            imports.add(
                    normalizeWhitespace(matcher.group(1))
                            + " <- "
                            + matcher.group(2)
            );
        }

        Matcher sideEffectMatcher = SIDE_EFFECT_IMPORT_PATTERN.matcher(source);
        while (sideEffectMatcher.find() && imports.size() < MAX_IMPORTS_PER_FILE) {
            imports.add(sideEffectMatcher.group(1));
        }

        return List.copyOf(imports);
    }

    private List<String> extractExports(
            String source
    ) {
        LinkedHashSet<String> exports = new LinkedHashSet<>();
        Matcher matcher = EXPORT_PATTERN.matcher(source);

        while (matcher.find() && exports.size() < MAX_EXPORTS_PER_FILE) {
            exports.add(matcher.group(1));
        }

        return List.copyOf(exports);
    }

    private List<String> extractFrameworkSignals(
            String sourcePath,
            String source
    ) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        String normalizedPath = sourcePath.toLowerCase(Locale.ROOT);

        addSignalIfPresent(signals, source, "defineProps", "Vue defineProps");
        addSignalIfPresent(signals, source, "defineEmits", "Vue defineEmits");
        addSignalIfPresent(signals, source, "defineExpose", "Vue defineExpose");
        addSignalIfPresent(signals, source, "defineModel", "Vue defineModel");
        addSignalIfPresent(signals, source, "ref(", "Vue ref");
        addSignalIfPresent(signals, source, "computed(", "Vue computed");
        addSignalIfPresent(signals, source, "watch(", "Vue watch");
        addSignalIfPresent(signals, source, "onMounted(", "Vue onMounted");
        addSignalIfPresent(signals, source, "createRouter(", "Vue Router");
        addSignalIfPresent(signals, source, "defineStore(", "Pinia defineStore");
        addSignalIfPresent(signals, source, "useState(", "React useState");
        addSignalIfPresent(signals, source, "useEffect(", "React useEffect");
        addSignalIfPresent(signals, source, "createBrowserRouter(", "React Router");
        addSignalIfPresent(signals, source, "axios.create(", "Axios client");
        addSignalIfPresent(signals, source, "fetch(", "Fetch API");

        if (normalizedPath.endsWith(".vue")) {
            signals.add("Vue SFC");
        }

        return signals.stream()
                .limit(MAX_FRAMEWORK_SIGNALS_PER_FILE)
                .toList();
    }

    private void addSignalIfPresent(
            LinkedHashSet<String> signals,
            String source,
            String token,
            String label
    ) {
        if (signals.size() >= MAX_FRAMEWORK_SIGNALS_PER_FILE) {
            return;
        }

        if (source.contains(token)) {
            signals.add(label);
        }
    }

    private List<String> extractPackageItems(
            String sourcePath,
            String source
    ) {
        String fileName = getFileName(sourcePath.toLowerCase(Locale.ROOT));
        if (!fileName.equals("package.json")) {
            return List.of();
        }

        LinkedHashSet<String> items = new LinkedHashSet<>();
        collectJsonSectionKeys(source, "scripts", "script", items);
        collectJsonSectionKeys(source, "dependencies", "dependency", items);
        collectJsonSectionKeys(source, "devDependencies", "devDependency", items);

        return items.stream()
                .limit(MAX_PACKAGE_ITEMS)
                .toList();
    }

    private void collectJsonSectionKeys(
            String source,
            String sectionName,
            String label,
            LinkedHashSet<String> items
    ) {
        if (items.size() >= MAX_PACKAGE_ITEMS) {
            return;
        }

        Pattern sectionPattern = Pattern.compile(
                "\\\"" + Pattern.quote(sectionName) + "\\\"\\s*:\\s*\\{([^}]*)}",
                Pattern.DOTALL
        );
        Matcher sectionMatcher = sectionPattern.matcher(source);

        if (!sectionMatcher.find()) {
            return;
        }

        Matcher keyMatcher = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:").matcher(sectionMatcher.group(1));
        while (keyMatcher.find() && items.size() < MAX_PACKAGE_ITEMS) {
            items.add(label + ": " + keyMatcher.group(1));
        }
    }

    private String formatSourceIndex(
            SourceIndex sourceIndex
    ) {
        StringBuilder index =
                new StringBuilder();

        index.append(
                        "\n===== SOURCE INDEX =====\n"
                )
                .append(
                        "SOURCE_PATH: "
                )
                .append(
                        sourceIndex.sourcePath()
                )
                .append(
                        "\n"
                );

        appendSingleValue(
                index,
                "PACKAGE",
                sourceIndex.packageName()
        );

        appendSingleValue(
                index,
                "TYPE",
                sourceIndex.type()
        );

        appendSingleValue(
                index,
                "CLASS",
                sourceIndex.className()
        );

        appendList(
                index,
                "ANNOTATIONS",
                sourceIndex.annotations()
        );

        appendList(
                index,
                "MAPPINGS",
                sourceIndex.mappings()
        );

        appendList(
                index,
                "METHODS",
                sourceIndex.methods()
        );

        appendList(
                index,
                "SCRIPT_FUNCTIONS",
                sourceIndex.scriptFunctions()
        );

        appendList(
                index,
                "FIELDS",
                sourceIndex.fields()
        );

        appendList(
                index,
                "IMPORTS",
                sourceIndex.imports()
        );

        appendList(
                index,
                "EXPORTS",
                sourceIndex.exports()
        );

        appendList(
                index,
                "FRAMEWORK_SIGNALS",
                sourceIndex.frameworkSignals()
        );

        appendList(
                index,
                "CONFIG_KEYS",
                sourceIndex.configKeys()
        );

        appendList(
                index,
                "PACKAGE_ITEMS",
                sourceIndex.packageItems()
        );

        index.append(
                "===== END SOURCE INDEX =====\n"
        );

        return index.toString();
    }

    private void appendSingleValue(
            StringBuilder content,
            String label,
            String value
    ) {
        if (
                value == null
                        || value.isBlank()
        ) {
            return;
        }

        content.append(
                        label
                )
                .append(
                        ": "
                )
                .append(
                        value
                )
                .append(
                        "\n"
                );
    }

    private void appendList(
            StringBuilder content,
            String label,
            List<String> values
    ) {
        if (
                values == null
                        || values.isEmpty()
        ) {
            return;
        }

        content.append(
                        label
                )
                .append(
                        ":\n"
                );

        for (String value : values) {
            content.append(
                            "- "
                    )
                    .append(
                            value
                    )
                    .append(
                            "\n"
                    );
        }
    }

    private String normalizeWhitespace(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }

    private record SourceCandidate(
            String sourcePath,
            int score
    ) {
    }

    private record TypeInfo(
            String type,
            String name
    ) {
    }

    private record SourceIndex(
            String sourcePath,
            String packageName,
            String type,
            String className,
            List<String> annotations,
            List<String> mappings,
            List<String> methods,
            List<String> scriptFunctions,
            List<String> fields,
            List<String> imports,
            List<String> exports,
            List<String> frameworkSignals,
            List<String> configKeys,
            List<String> packageItems
    ) {
    }

    private record CodebaseIndexBuildResult(
            String content,
            int indexedFileCount,
            boolean contentLimitReached
    ) {
    }
}