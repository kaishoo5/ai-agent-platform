package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.infra.filesystem.FileSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddImportTool implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES =
            1_048_576L;

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile(
                    "(?m)^[\\t ]*package[\\t ]+[\\w.]+[\\t ]*;[\\t ]*$"
            );

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile(
                    "(?m)^[\\t ]*import[\\t ]+(?:static[\\t ]+)?[\\w.$*]+[\\t ]*;[\\t ]*$"
            );

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "add_import",
                    "작업 폴더 안의 Java 클래스에 import 문을 추가합니다. "
                            + "이미 동일한 import가 있으면 중복으로 추가하지 않습니다.",
                    Map.of(
                            "className",
                            new ToolParameter(
                                    "string",
                                    "import를 추가할 Java 클래스 이름입니다. .java 확장자는 생략할 수 있습니다. 예: ChatOrchestrator",
                                    true
                            ),
                            "importName",
                            new ToolParameter(
                                    "string",
                                    "추가할 import 대상입니다. 예: java.time.LocalDateTime 또는 import java.time.LocalDateTime;",
                                    true
                            ),
                            "path",
                            new ToolParameter(
                                    "string",
                                    "검색을 시작할 작업 폴더 기준 상대 경로입니다. 생략하면 작업 폴더 최상위에서 검색합니다.",
                                    false
                            ),
                            "createBackup",
                            new ToolParameter(
                                    "boolean",
                                    "수정 전 .bak 백업 파일 생성 여부입니다. 생략하면 true입니다.",
                                    false
                            )
                    )
            );

    private final FileSystemProperties fileSystemProperties;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String className =
                getStringArgument(
                        arguments,
                        "className"
                );

        String importName =
                getStringArgument(
                        arguments,
                        "importName"
                );

        String requestedPath =
                getStringArgument(
                        arguments,
                        "path"
                );

        boolean createBackup =
                getBooleanArgument(
                        arguments,
                        "createBackup",
                        true
                );

        if (className == null) {
            return ToolResult.failure(
                    "import를 추가할 클래스 이름이 없습니다."
            );
        }

        if (importName == null) {
            return ToolResult.failure(
                    "추가할 import가 없습니다."
            );
        }

        if (requestedPath == null) {
            requestedPath =
                    "";
        }

        String normalizedClassName =
                normalizeClassName(
                        className
                );

        String normalizedImport =
                normalizeImport(
                        importName
                );

        if (normalizedImport == null) {
            return ToolResult.failure(
                    "올바른 Java import 형식이 아닙니다: "
                            + importName
            );
        }

        try {
            Path workspaceRoot =
                    Path.of(
                                    fileSystemProperties.getRootDirectory()
                            )
                            .toAbsolutePath()
                            .normalize();

            Path searchRoot =
                    workspaceRoot.resolve(
                                    requestedPath
                            )
                            .normalize();

            if (!searchRoot.startsWith(workspaceRoot)) {
                return ToolResult.failure(
                        "작업 폴더 외부의 파일은 수정할 수 없습니다."
                );
            }

            if (!Files.exists(searchRoot)) {
                return ToolResult.failure(
                        "검색 경로가 존재하지 않습니다: "
                                + displayRequestedPath(
                                requestedPath
                        )
                );
            }

            if (!Files.isDirectory(searchRoot)) {
                return ToolResult.failure(
                        "검색 경로가 폴더가 아닙니다: "
                                + displayRequestedPath(
                                requestedPath
                        )
                );
            }

            List<Path> matchedClassFiles =
                    findJavaFiles(
                            searchRoot,
                            normalizedClassName
                    );

            if (matchedClassFiles.isEmpty()) {
                return ToolResult.failure(
                        "Java 클래스를 찾을 수 없습니다: "
                                + normalizedClassName
                );
            }

            if (matchedClassFiles.size() > 1) {
                return ToolResult.failure(
                        buildMultipleClassMatchesContent(
                                workspaceRoot,
                                normalizedClassName,
                                matchedClassFiles
                        )
                );
            }

            Path classFile =
                    matchedClassFiles.getFirst();

            if (!Files.isReadable(classFile)) {
                return ToolResult.failure(
                        "클래스 파일을 읽을 수 없습니다: "
                                + normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                );
            }

            if (!Files.isWritable(classFile)) {
                return ToolResult.failure(
                        "클래스 파일을 수정할 수 없습니다: "
                                + normalizePath(
                                workspaceRoot.relativize(
                                        classFile
                                )
                        )
                );
            }

            long fileSize =
                    Files.size(
                            classFile
                    );

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return ToolResult.failure(
                        "클래스 파일이 너무 커서 수정할 수 없습니다. 최대 크기: "
                                + MAX_FILE_SIZE_BYTES
                                + " bytes, 실제 크기: "
                                + fileSize
                                + " bytes"
                );
            }

            String sourceCode =
                    Files.readString(
                            classFile,
                            StandardCharsets.UTF_8
                    );

            if (
                    containsImport(
                            sourceCode,
                            normalizedImport
                    )
            ) {
                return ToolResult.success(
                        "이미 import가 존재합니다.\n"
                                + "className: "
                                + normalizedClassName
                                + "\nimport: "
                                + normalizedImport
                );
            }

            String updatedSourceCode =
                    addImport(
                            sourceCode,
                            normalizedImport
                    );

            Path backupFile =
                    null;

            if (createBackup) {
                backupFile =
                        createBackup(
                                classFile
                        );
            }

            Files.writeString(
                    classFile,
                    updatedSourceCode,
                    StandardCharsets.UTF_8
            );

            String relativePath =
                    normalizePath(
                            workspaceRoot.relativize(
                                    classFile
                            )
                    );

            log.info(
                    "Add Import Tool 실행 완료. className={}, importName={}, path={}, backupCreated={}",
                    normalizedClassName,
                    normalizedImport,
                    relativePath,
                    createBackup
            );

            StringBuilder result =
                    new StringBuilder();

            result.append(
                    "Java import 추가가 완료되었습니다."
            );

            result.append(
                    "\nclassName: "
            ).append(
                    normalizedClassName
            );

            result.append(
                    "\nimport: "
            ).append(
                    normalizedImport
            );

            result.append(
                    "\npath: "
            ).append(
                    relativePath
            );

            if (backupFile != null) {
                result.append(
                        "\nbackup: "
                ).append(
                        normalizePath(
                                workspaceRoot.relativize(
                                        backupFile
                                )
                        )
                );
            }

            return ToolResult.success(
                    result.toString()
            );
        } catch (IOException exception) {
            log.error(
                    "Add Import Tool 실행 실패. className={}, importName={}, path={}",
                    className,
                    importName,
                    displayRequestedPath(
                            requestedPath
                    ),
                    exception
            );

            return ToolResult.failure(
                    "Java import를 추가하는 중 파일 처리 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Add Import Tool 처리 실패. className={}, importName={}, path={}",
                    className,
                    importName,
                    displayRequestedPath(
                            requestedPath
                    ),
                    exception
            );

            return ToolResult.failure(
                    "Java import 추가 요청을 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private String addImport(
            String sourceCode,
            String importName
    ) {
        String lineSeparator =
                detectLineSeparator(
                        sourceCode
                );

        String importLine =
                "import "
                        + importName
                        + ";";

        Matcher importMatcher =
                IMPORT_PATTERN.matcher(
                        sourceCode
                );

        int lastImportEnd =
                -1;

        while (importMatcher.find()) {
            lastImportEnd =
                    importMatcher.end();
        }

        if (lastImportEnd >= 0) {
            return sourceCode.substring(
                    0,
                    lastImportEnd
            )
                    + lineSeparator
                    + importLine
                    + sourceCode.substring(
                    lastImportEnd
            );
        }

        Matcher packageMatcher =
                PACKAGE_PATTERN.matcher(
                        sourceCode
                );

        if (packageMatcher.find()) {
            int packageEnd =
                    packageMatcher.end();

            return sourceCode.substring(
                    0,
                    packageEnd
            )
                    + lineSeparator
                    + lineSeparator
                    + importLine
                    + sourceCode.substring(
                    packageEnd
            );
        }

        return importLine
                + lineSeparator
                + lineSeparator
                + sourceCode;
    }

    private boolean containsImport(
            String sourceCode,
            String importName
    ) {
        Pattern pattern =
                Pattern.compile(
                        "(?m)^[\\t ]*import[\\t ]+"
                                + Pattern.quote(
                                importName
                        )
                                + "[\\t ]*;[\\t ]*$"
                );

        return pattern.matcher(
                sourceCode
        ).find();
    }

    private String normalizeImport(
            String importName
    ) {
        String normalized =
                importName.trim();

        if (
                normalized.startsWith(
                        "import "
                )
        ) {
            normalized =
                    normalized.substring(
                            "import ".length()
                    ).trim();
        }

        if (
                normalized.endsWith(
                        ";"
                )
        ) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 1
                    ).trim();
        }

        boolean staticImport =
                false;

        if (
                normalized.startsWith(
                        "static "
                )
        ) {
            staticImport =
                    true;

            normalized =
                    normalized.substring(
                            "static ".length()
                    ).trim();
        }

        if (
                !normalized.matches(
                        "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*"
                )
        ) {
            return null;
        }

        if (staticImport) {
            return "static "
                    + normalized;
        }

        return normalized;
    }

    private List<Path> findJavaFiles(
            Path searchRoot,
            String className
    ) throws IOException {
        String expectedFileName =
                className.toLowerCase(
                        Locale.ROOT
                )
                        + ".java";

        try (
                Stream<Path> stream =
                        Files.walk(
                                searchRoot
                        )
        ) {
            return stream.filter(
                            Files::isRegularFile
                    )
                    .filter(path ->
                            !isIgnoredPath(
                                    searchRoot,
                                    path
                            )
                    )
                    .filter(path ->
                            fileName(
                                    path
                            )
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                                    .equals(
                                            expectedFileName
                                    )
                    )
                    .sorted(
                            Comparator.comparing(path ->
                                    normalizePath(
                                            searchRoot.relativize(
                                                    path
                                            )
                                    )
                            )
                    )
                    .limit(
                            MAX_CLASS_SEARCH_RESULTS
                    )
                    .toList();
        }
    }

    private boolean isIgnoredPath(
            Path searchRoot,
            Path path
    ) {
        Path relativePath =
                searchRoot.relativize(
                        path
                );

        for (Path part : relativePath) {
            String name =
                    part.toString()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (
                    name.equals(
                            ".git"
                    )
                            || name.equals(
                            ".idea"
                    )
                            || name.equals(
                            "build"
                    )
                            || name.equals(
                            "target"
                    )
                            || name.equals(
                            "node_modules"
                    )
                            || name.equals(
                            ".ai-agent-transactions"
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private String normalizeClassName(
            String className
    ) {
        String normalized =
                className.trim();

        if (
                normalized.toLowerCase(
                        Locale.ROOT
                ).endsWith(
                        ".java"
                )
        ) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 5
                    );
        }

        return normalized;
    }

    private String fileName(
            Path path
    ) {
        Path fileName =
                path.getFileName();

        return fileName == null
                ? ""
                : fileName.toString();
    }

    private String detectLineSeparator(
            String sourceCode
    ) {
        if (
                sourceCode.contains(
                        "\r\n"
                )
        ) {
            return "\r\n";
        }

        return "\n";
    }

    private Path createBackup(
            Path classFile
    ) throws IOException {
        Path backupFile =
                classFile.resolveSibling(
                        classFile.getFileName()
                                + ".bak"
                );

        Files.copy(
                classFile,
                backupFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );

        return backupFile;
    }

    private boolean getBooleanArgument(
            Map<String, Object> arguments,
            String name,
            boolean defaultValue
    ) {
        if (
                arguments == null
                        || !arguments.containsKey(
                        name
                )
        ) {
            return defaultValue;
        }

        Object value =
                arguments.get(
                        name
                );

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(
                    stringValue
            );
        }

        return defaultValue;
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

    private String displayRequestedPath(
            String requestedPath
    ) {
        if (
                requestedPath == null
                        || requestedPath.isBlank()
        ) {
            return ".";
        }

        return requestedPath;
    }

    private String normalizePath(
            Path path
    ) {
        return path.toString()
                .replace(
                        '\\',
                        '/'
                );
    }

    private String buildMultipleClassMatchesContent(
            Path workspaceRoot,
            String className,
            List<Path> matchedClassFiles
    ) {
        StringBuilder builder =
                new StringBuilder();

        builder.append(
                "동일한 클래스 이름의 Java 파일이 여러 개 발견되었습니다."
        );

        builder.append(
                "\nclassName: "
        ).append(
                className
        );

        builder.append(
                "\n검색 결과:"
        );

        for (Path path : matchedClassFiles) {
            builder.append(
                    "\n- "
            ).append(
                    normalizePath(
                            workspaceRoot.relativize(
                                    path
                            )
                    )
            );
        }

        builder.append(
                "\npath 값을 더 구체적으로 지정해주세요."
        );

        return builder.toString();
    }
}