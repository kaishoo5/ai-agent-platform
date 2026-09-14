package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import com.agent.aiagent.infra.filesystem.FileSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodebaseScannerTool implements AgentTool {

    private static final int MAX_SCANNED_FILES = 20_000;
    private static final int MAX_DISPLAY_ITEMS = 50;
    private static final int MAX_PACKAGE_DIRECTORIES = 200;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "codebase_scan",
                    "작업 폴더 안의 프로젝트를 스캔하여 빌드 도구, 설정 파일, Java 패키지 구조와 주요 클래스의 이름 및 상대 경로를 제공합니다. 사용자가 프로젝트 구조를 요청하면 결과의 클래스 목록과 패키지 구조를 개수로만 요약하지 말고 표시해야 합니다.",
                    Map.of(
                            "path",
                            new ToolParameter(
                                    "string",
                                    "스캔할 프로젝트의 작업 폴더 기준 상대 경로입니다. 생략하면 작업 폴더 최상위 경로를 사용합니다.",
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
        String requestedPath =
                getArgument(
                        arguments,
                        "path"
                );

        if (requestedPath == null) {
            requestedPath = "";
        }

        try {
            Path workspaceRoot =
                    Path.of(
                                    fileSystemProperties.getRootDirectory()
                            )
                            .toAbsolutePath()
                            .normalize();

            Path projectRoot =
                    workspaceRoot.resolve(
                                    requestedPath
                            )
                            .normalize();

            if (!projectRoot.startsWith(workspaceRoot)) {
                return ToolResult.failure(
                        "작업 폴더 외부의 프로젝트는 스캔할 수 없습니다."
                );
            }

            if (!Files.exists(projectRoot)) {
                return ToolResult.failure(
                        "프로젝트 경로가 존재하지 않습니다: "
                                + displayRequestedPath(
                                requestedPath
                        )
                );
            }

            if (!Files.isDirectory(projectRoot)) {
                return ToolResult.failure(
                        "프로젝트 경로가 폴더가 아닙니다: "
                                + displayRequestedPath(
                                requestedPath
                        )
                );
            }

            List<Path> files =
                    scanFiles(
                            projectRoot
                    );

            CodebaseScanResult result =
                    analyze(
                            projectRoot,
                            files
                    );

            String content =
                    buildContent(
                            workspaceRoot,
                            projectRoot,
                            result
                    );

            log.info(
                    "Codebase Scanner Tool 실행 완료. path={}, fileCount={}, javaFileCount={}, sourceRootCount={}",
                    displayRequestedPath(requestedPath),
                    result.totalFileCount(),
                    result.javaFiles().size(),
                    result.packageStructures().size()
            );

            return ToolResult.success(
                    content
            );
        } catch (IOException exception) {
            log.error(
                    "Codebase Scanner Tool 실행 실패. path={}",
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "프로젝트를 스캔하는 중 오류가 발생했습니다."
            );
        } catch (Exception exception) {
            log.error(
                    "Codebase Scanner Tool 처리 실패. path={}",
                    displayRequestedPath(requestedPath),
                    exception
            );

            return ToolResult.failure(
                    "프로젝트 경로를 처리하는 중 오류가 발생했습니다."
            );
        }
    }

    private List<Path> scanFiles(
            Path projectRoot
    ) throws IOException {
        try (
                Stream<Path> stream =
                        Files.walk(
                                projectRoot
                        )
        ) {
            return stream.filter(
                            Files::isRegularFile
                    )
                    .filter(path ->
                            !isIgnoredPath(
                                    projectRoot,
                                    path
                            )
                    )
                    .sorted(
                            Comparator.comparing(path ->
                                    normalizePath(
                                            projectRoot.relativize(
                                                    path
                                            )
                                    )
                            )
                    )
                    .limit(
                            MAX_SCANNED_FILES + 1L
                    )
                    .toList();
        }
    }

    private CodebaseScanResult analyze(
            Path projectRoot,
            List<Path> scannedFiles
    ) {
        boolean scanLimitReached =
                scannedFiles.size() > MAX_SCANNED_FILES;

        List<Path> files =
                scanLimitReached
                        ? scannedFiles.subList(
                        0,
                        MAX_SCANNED_FILES
                )
                        : scannedFiles;

        List<Path> javaFiles =
                filterFiles(
                        files,
                        path ->
                                fileName(path)
                                        .toLowerCase(
                                                Locale.ROOT
                                        )
                                        .endsWith(".java")
                );

        List<Path> buildFiles =
                filterFiles(
                        files,
                        this::isBuildFile
                );

        List<Path> applicationConfigFiles =
                filterFiles(
                        files,
                        this::isApplicationConfigFile
                );

        List<Path> readmeFiles =
                filterFiles(
                        files,
                        path ->
                                fileName(path)
                                        .toLowerCase(
                                                Locale.ROOT
                                        )
                                        .startsWith("readme")
                );

        List<Path> applicationClasses =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Application.java")
                );

        List<Path> controllers =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Controller.java")
                                        || containsDirectory(
                                        projectRoot,
                                        path,
                                        "controller"
                                )
                );

        List<Path> services =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Service.java")
                                        || fileName(path)
                                        .endsWith("ServiceImpl.java")
                                        || containsDirectory(
                                        projectRoot,
                                        path,
                                        "service"
                                )
                );

        List<Path> repositories =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Repository.java")
                                        || containsDirectory(
                                        projectRoot,
                                        path,
                                        "repository"
                                )
                );

        List<Path> mappers =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Mapper.java")
                                        || containsDirectory(
                                        projectRoot,
                                        path,
                                        "mapper"
                                )
                );

        List<Path> entities =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Entity.java")
                                        || containsDirectory(
                                        projectRoot,
                                        path,
                                        "entity"
                                )
                );

        List<Path> configurations =
                filterFiles(
                        javaFiles,
                        path ->
                                fileName(path)
                                        .endsWith("Config.java")
                                        || fileName(path)
                                        .endsWith("Configuration.java")
                                        || containsDirectory(
                                        projectRoot,
                                        path,
                                        "config"
                                )
                );

        List<Path> tests =
                filterFiles(
                        javaFiles,
                        path ->
                                containsDirectory(
                                        projectRoot,
                                        path,
                                        "test"
                                )
                                        || fileName(path)
                                        .endsWith("Test.java")
                                        || fileName(path)
                                        .endsWith("Tests.java")
                );

        Map<String, PackageNode> packageStructures =
                buildPackageStructures(
                        projectRoot,
                        javaFiles
                );

        return new CodebaseScanResult(
                files.size(),
                scanLimitReached,
                buildFiles,
                applicationConfigFiles,
                readmeFiles,
                javaFiles,
                applicationClasses,
                controllers,
                services,
                repositories,
                mappers,
                entities,
                configurations,
                tests,
                packageStructures
        );
    }

    private Map<String, PackageNode> buildPackageStructures(
            Path projectRoot,
            List<Path> javaFiles
    ) {
        Map<String, PackageNode> packageStructures =
                new TreeMap<>();

        for (Path javaFile : javaFiles) {
            Path relativePath =
                    projectRoot.relativize(
                            javaFile
                    );

            int sourceRootEndIndex =
                    findSourceRootEndIndex(
                            relativePath
                    );

            if (sourceRootEndIndex < 0) {
                continue;
            }

            Path sourceRootPath =
                    relativePath.subpath(
                            0,
                            sourceRootEndIndex
                    );

            String sourceRoot =
                    normalizePath(
                            sourceRootPath
                    );

            PackageNode rootNode =
                    packageStructures.computeIfAbsent(
                            sourceRoot,
                            key ->
                                    new PackageNode()
                    );

            PackageNode currentNode =
                    rootNode;

            for (
                    int index = sourceRootEndIndex;
                    index < relativePath.getNameCount() - 1;
                    index++
            ) {
                String directoryName =
                        relativePath.getName(
                                        index
                                )
                                .toString();

                currentNode =
                        currentNode.children()
                                .computeIfAbsent(
                                        directoryName,
                                        key ->
                                                new PackageNode()
                                );
            }
        }

        return packageStructures;
    }

    private int findSourceRootEndIndex(
            Path relativePath
    ) {
        int nameCount =
                relativePath.getNameCount();

        for (
                int index = 0;
                index <= nameCount - 3;
                index++
        ) {
            boolean sourceRoot =
                    "src".equalsIgnoreCase(
                            relativePath.getName(
                                            index
                                    )
                                    .toString()
                    )
                            && (
                            "main".equalsIgnoreCase(
                                    relativePath.getName(
                                                    index + 1
                                            )
                                            .toString()
                            )
                                    || "test".equalsIgnoreCase(
                                    relativePath.getName(
                                                    index + 1
                                            )
                                            .toString()
                            )
                    )
                            && "java".equalsIgnoreCase(
                            relativePath.getName(
                                            index + 2
                                    )
                                    .toString()
                    );

            if (sourceRoot) {
                return index + 3;
            }
        }

        return -1;
    }

    private List<Path> filterFiles(
            List<Path> files,
            Predicate<Path> predicate
    ) {
        return files.stream()
                .filter(
                        predicate
                )
                .toList();
    }

    private boolean isBuildFile(
            Path path
    ) {
        String fileName =
                fileName(path)
                        .toLowerCase(
                                Locale.ROOT
                        );

        return fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")
                || fileName.equals("settings.gradle")
                || fileName.equals("settings.gradle.kts")
                || fileName.equals("pom.xml")
                || fileName.equals("gradlew")
                || fileName.equals("gradlew.bat");
    }

    private boolean isApplicationConfigFile(
            Path path
    ) {
        String fileName =
                fileName(path)
                        .toLowerCase(
                                Locale.ROOT
                        );

        return fileName.startsWith("application")
                && (
                fileName.endsWith(".properties")
                        || fileName.endsWith(".yml")
                        || fileName.endsWith(".yaml")
        );
    }

    private boolean containsDirectory(
            Path projectRoot,
            Path path,
            String directoryName
    ) {
        Path relativePath =
                projectRoot.relativize(
                        path
                );

        for (Path part : relativePath) {
            if (
                    directoryName.equalsIgnoreCase(
                            part.toString()
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private boolean isIgnoredPath(
            Path projectRoot,
            Path path
    ) {
        Path relativePath =
                projectRoot.relativize(
                        path
                );

        for (Path part : relativePath) {
            String name =
                    part.toString()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (
                    name.equals(".git")
                            || name.equals(".gradle")
                            || name.equals(".idea")
                            || name.equals("build")
                            || name.equals("target")
                            || name.equals("node_modules")
                            || name.equals("dist")
                            || name.equals("out")
            ) {
                return true;
            }
        }

        return false;
    }

    private String buildContent(
            Path workspaceRoot,
            Path projectRoot,
            CodebaseScanResult result
    ) {
        String relativeProjectPath =
                workspaceRoot.equals(projectRoot)
                        ? "."
                        : normalizePath(
                        workspaceRoot.relativize(
                                projectRoot
                        )
                );

        StringBuilder content =
                new StringBuilder();

        content.append("프로젝트 스캔 결과\n\n");

        content.append("[최종 응답 작성 규칙]\n")
                .append("- 아래 Java 패키지 구조를 생략하지 않습니다.\n")
                .append("- 주요 클래스 목록을 개수로만 요약하지 않습니다.\n")
                .append("- Controller, Service, Repository, Mapper, Entity, Configuration의 클래스 이름과 상대 경로를 표시합니다.\n")
                .append("- 목록이 너무 많으면 각 분류별 최대 ")
                .append(MAX_DISPLAY_ITEMS)
                .append("개까지 표시하고 나머지 개수를 알립니다.\n\n");

        content.append("프로젝트 경로: ")
                .append(relativeProjectPath)
                .append("\n");

        content.append("전체 파일 수: ")
                .append(result.totalFileCount())
                .append("\n");

        content.append("Java 파일 수: ")
                .append(result.javaFiles().size())
                .append("\n");

        content.append("추정 빌드 방식: ")
                .append(
                        detectBuildSystem(
                                result.buildFiles()
                        )
                )
                .append("\n");

        if (result.scanLimitReached()) {
            content.append("주의: 파일이 많아 최대 ")
                    .append(MAX_SCANNED_FILES)
                    .append("개까지만 스캔했습니다.\n");
        }

        appendFileSection(
                content,
                projectRoot,
                "빌드 관련 파일",
                result.buildFiles()
        );

        appendFileSection(
                content,
                projectRoot,
                "애플리케이션 설정 파일",
                result.applicationConfigFiles()
        );

        appendFileSection(
                content,
                projectRoot,
                "README 파일",
                result.readmeFiles()
        );

        appendPackageStructureSection(
                content,
                result.packageStructures()
        );

        appendClassSection(
                content,
                projectRoot,
                "애플리케이션 진입점",
                result.applicationClasses()
        );

        appendClassSection(
                content,
                projectRoot,
                "Controller",
                result.controllers()
        );

        appendClassSection(
                content,
                projectRoot,
                "Service",
                result.services()
        );

        appendClassSection(
                content,
                projectRoot,
                "Repository",
                result.repositories()
        );

        appendClassSection(
                content,
                projectRoot,
                "Mapper",
                result.mappers()
        );

        appendClassSection(
                content,
                projectRoot,
                "Entity",
                result.entities()
        );

        appendClassSection(
                content,
                projectRoot,
                "Configuration",
                result.configurations()
        );

        appendClassSection(
                content,
                projectRoot,
                "Test",
                result.tests()
        );

        return content.toString()
                .trim();
    }

    private void appendPackageStructureSection(
            StringBuilder content,
            Map<String, PackageNode> packageStructures
    ) {
        content.append("\n\n## Java 패키지 구조");

        if (packageStructures.isEmpty()) {
            content.append("\n\n- 확인되지 않음");
            return;
        }

        int[] displayedDirectoryCount =
                new int[]{
                        0
                };

        for (
                Map.Entry<String, PackageNode> entry
                : packageStructures.entrySet()
        ) {
            content.append("\n\n")
                    .append(entry.getKey());

            appendPackageNode(
                    content,
                    entry.getValue(),
                    0,
                    displayedDirectoryCount
            );

            if (
                    displayedDirectoryCount[0]
                            >= MAX_PACKAGE_DIRECTORIES
            ) {
                content.append("\n- 패키지 디렉터리가 많아 최대 ")
                        .append(MAX_PACKAGE_DIRECTORIES)
                        .append("개까지만 표시합니다.");

                return;
            }
        }
    }

    private void appendPackageNode(
            StringBuilder content,
            PackageNode node,
            int depth,
            int[] displayedDirectoryCount
    ) {
        for (
                Map.Entry<String, PackageNode> entry
                : node.children().entrySet()
        ) {
            if (
                    displayedDirectoryCount[0]
                            >= MAX_PACKAGE_DIRECTORIES
            ) {
                return;
            }

            content.append("\n")
                    .append("  ".repeat(depth))
                    .append("- ")
                    .append(entry.getKey());

            displayedDirectoryCount[0]++;

            appendPackageNode(
                    content,
                    entry.getValue(),
                    depth + 1,
                    displayedDirectoryCount
            );
        }
    }

    private void appendFileSection(
            StringBuilder content,
            Path projectRoot,
            String title,
            List<Path> files
    ) {
        content.append("\n\n## ")
                .append(title)
                .append(" (")
                .append(files.size())
                .append("개)");

        if (files.isEmpty()) {
            content.append("\n\n- 없음");
            return;
        }

        int displayCount =
                Math.min(
                        files.size(),
                        MAX_DISPLAY_ITEMS
                );

        for (int index = 0; index < displayCount; index++) {
            Path file =
                    files.get(index);

            content.append("\n\n- `")
                    .append(
                            normalizePath(
                                    projectRoot.relativize(
                                            file
                                    )
                            )
                    )
                    .append("`");
        }

        if (files.size() > MAX_DISPLAY_ITEMS) {
            content.append("\n\n- 외 ")
                    .append(
                            files.size() - MAX_DISPLAY_ITEMS
                    )
                    .append("개");
        }
    }

    private void appendClassSection(
            StringBuilder content,
            Path projectRoot,
            String title,
            List<Path> files
    ) {
        content.append("\n\n## ")
                .append(title)
                .append(" (")
                .append(files.size())
                .append("개)");

        if (files.isEmpty()) {
            content.append("\n\n- 없음");
            return;
        }

        int displayCount =
                Math.min(
                        files.size(),
                        MAX_DISPLAY_ITEMS
                );

        for (int index = 0; index < displayCount; index++) {
            Path file =
                    files.get(index);

            content.append("\n\n- ")
                    .append(
                            removeJavaExtension(
                                    fileName(
                                            file
                                    )
                            )
                    )
                    .append("\n  `")
                    .append(
                            normalizePath(
                                    projectRoot.relativize(
                                            file
                                    )
                            )
                    )
                    .append("`");
        }

        if (files.size() > MAX_DISPLAY_ITEMS) {
            content.append("\n\n- 외 ")
                    .append(
                            files.size() - MAX_DISPLAY_ITEMS
                    )
                    .append("개");
        }
    }

    private String detectBuildSystem(
            List<Path> buildFiles
    ) {
        boolean hasGradle =
                buildFiles.stream()
                        .map(
                                this::fileName
                        )
                        .map(name ->
                                name.toLowerCase(
                                        Locale.ROOT
                                )
                        )
                        .anyMatch(name ->
                                name.equals("build.gradle")
                                        || name.equals("build.gradle.kts")
                        );

        boolean hasMaven =
                buildFiles.stream()
                        .map(
                                this::fileName
                        )
                        .anyMatch(name ->
                                name.equalsIgnoreCase(
                                        "pom.xml"
                                )
                        );

        if (hasGradle && hasMaven) {
            return "Gradle, Maven";
        }

        if (hasGradle) {
            return "Gradle";
        }

        if (hasMaven) {
            return "Maven";
        }

        return "확인되지 않음";
    }

    private String removeJavaExtension(
            String fileName
    ) {
        if (
                fileName.toLowerCase(
                                Locale.ROOT
                        )
                        .endsWith(".java")
        ) {
            return fileName.substring(
                    0,
                    fileName.length() - ".java".length()
            );
        }

        return fileName;
    }

    private String getArgument(
            Map<String, Object> arguments,
            String key
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        key
                );

        if (value == null) {
            return null;
        }

        String text =
                value.toString()
                        .trim();

        return text.isBlank()
                ? null
                : text;
    }

    private String fileName(
            Path path
    ) {
        return path.getFileName()
                .toString();
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

    private String displayRequestedPath(
            String requestedPath
    ) {
        return requestedPath == null
                || requestedPath.isBlank()
                ? "."
                : requestedPath;
    }

    private record CodebaseScanResult(
            int totalFileCount,
            boolean scanLimitReached,
            List<Path> buildFiles,
            List<Path> applicationConfigFiles,
            List<Path> readmeFiles,
            List<Path> javaFiles,
            List<Path> applicationClasses,
            List<Path> controllers,
            List<Path> services,
            List<Path> repositories,
            List<Path> mappers,
            List<Path> entities,
            List<Path> configurations,
            List<Path> tests,
            Map<String, PackageNode> packageStructures
    ) {
    }

    private static class PackageNode {

        private final Map<String, PackageNode> children =
                new TreeMap<>();

        public Map<String, PackageNode> children() {
            return children;
        }
    }
}