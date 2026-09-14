package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.CodeEditPatch;
import com.agent.aiagent.infra.filesystem.FileSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeEditTransactionManager {

    private static final int MAX_CLASS_SEARCH_RESULTS =
            50;

    private static final String TRANSACTION_DIRECTORY_NAME =
            ".ai-agent-transactions";

    private final FileSystemProperties fileSystemProperties;

    public CodeEditTransaction begin(
            List<CodeEditPatch> patches
    ) throws IOException {
        if (
                patches == null
                        || patches.isEmpty()
        ) {
            throw new IllegalArgumentException(
                    "Transaction을 시작할 Patch가 없습니다."
            );
        }

        Path workspaceRoot =
                getWorkspaceRoot();

        String transactionId =
                UUID.randomUUID()
                        .toString();

        Path transactionRoot =
                workspaceRoot.resolve(
                                TRANSACTION_DIRECTORY_NAME
                        )
                        .resolve(
                                transactionId
                        )
                        .normalize();

        if (!transactionRoot.startsWith(workspaceRoot)) {
            throw new IllegalStateException(
                    "Transaction 경로가 작업 폴더 외부입니다."
            );
        }

        Files.createDirectories(
                transactionRoot
        );

        Map<Path, Path> snapshots =
                new LinkedHashMap<>();

        try {
            for (CodeEditPatch patch : patches) {
                Path sourceFile =
                        resolveSourceFile(
                                workspaceRoot,
                                patch
                        );

                if (
                        snapshots.containsKey(
                                sourceFile
                        )
                ) {
                    continue;
                }

                Path relativePath =
                        workspaceRoot.relativize(
                                sourceFile
                        );

                Path snapshotFile =
                        transactionRoot.resolve(
                                        relativePath
                                )
                                .normalize();

                if (!snapshotFile.startsWith(transactionRoot)) {
                    throw new IllegalStateException(
                            "Snapshot 경로가 Transaction 폴더 외부입니다."
                    );
                }

                Path parent =
                        snapshotFile.getParent();

                if (parent != null) {
                    Files.createDirectories(
                            parent
                    );
                }

                Files.copy(
                        sourceFile,
                        snapshotFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );

                snapshots.put(
                        sourceFile,
                        snapshotFile
                );

                log.info(
                        "Code Edit Snapshot 생성. transactionId={}, source={}",
                        transactionId,
                        normalizePath(
                                relativePath
                        )
                );
            }

            log.info(
                    "Code Edit Transaction 시작. transactionId={}, snapshotCount={}",
                    transactionId,
                    snapshots.size()
            );

            return new CodeEditTransaction(
                    transactionId,
                    transactionRoot,
                    Map.copyOf(
                            snapshots
                    )
            );
        } catch (Exception exception) {
            deleteDirectoryQuietly(
                    transactionRoot
            );

            throw exception;
        }
    }

    public void commit(
            CodeEditTransaction transaction
    ) {
        if (transaction == null) {
            return;
        }

        deleteDirectoryQuietly(
                transaction.transactionRoot()
        );

        log.info(
                "Code Edit Transaction Commit 완료. transactionId={}",
                transaction.transactionId()
        );
    }

    public void rollback(
            CodeEditTransaction transaction
    ) {
        if (transaction == null) {
            return;
        }

        List<String> rollbackFailures =
                new ArrayList<>();

        for (
                Map.Entry<Path, Path> entry
                : transaction.snapshots().entrySet()
        ) {
            Path sourceFile =
                    entry.getKey();

            Path snapshotFile =
                    entry.getValue();

            try {
                if (!Files.exists(snapshotFile)) {
                    rollbackFailures.add(
                            sourceFile.toString()
                                    + ": snapshot 없음"
                    );

                    continue;
                }

                Files.copy(
                        snapshotFile,
                        sourceFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );

                log.info(
                        "Code Edit Rollback 복구 완료. transactionId={}, source={}",
                        transaction.transactionId(),
                        normalizePath(
                                sourceFile
                        )
                );
            } catch (Exception exception) {
                log.error(
                        "Code Edit Rollback 복구 실패. transactionId={}, source={}",
                        transaction.transactionId(),
                        sourceFile,
                        exception
                );

                rollbackFailures.add(
                        sourceFile.toString()
                                + ": "
                                + exception.getMessage()
                );
            }
        }

        deleteDirectoryQuietly(
                transaction.transactionRoot()
        );

        if (!rollbackFailures.isEmpty()) {
            throw new IllegalStateException(
                    "일부 파일 Rollback에 실패했습니다: "
                            + String.join(
                            ", ",
                            rollbackFailures
                    )
            );
        }

        log.info(
                "Code Edit Transaction Rollback 완료. transactionId={}, restoredFileCount={}",
                transaction.transactionId(),
                transaction.snapshots().size()
        );
    }

    private Path resolveSourceFile(
            Path workspaceRoot,
            CodeEditPatch patch
    ) throws IOException {
        if (patch == null) {
            throw new IllegalArgumentException(
                    "Patch가 null입니다."
            );
        }

        if (
                !StringUtils.hasText(
                        patch.className()
                )
        ) {
            throw new IllegalArgumentException(
                    "Patch className이 없습니다."
            );
        }

        String requestedPath =
                StringUtils.hasText(
                        patch.path()
                )
                        ? patch.path()
                        : "";

        Path searchRoot =
                workspaceRoot.resolve(
                                requestedPath
                        )
                        .normalize();

        if (!searchRoot.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException(
                    "작업 폴더 외부의 파일은 수정할 수 없습니다."
            );
        }

        if (!Files.exists(searchRoot)) {
            throw new IllegalArgumentException(
                    "검색 경로가 존재하지 않습니다: "
                            + displayRequestedPath(
                            requestedPath
                    )
            );
        }

        if (!Files.isDirectory(searchRoot)) {
            throw new IllegalArgumentException(
                    "검색 경로가 폴더가 아닙니다: "
                            + displayRequestedPath(
                            requestedPath
                    )
            );
        }

        String normalizedClassName =
                normalizeClassName(
                        patch.className()
                );

        List<Path> matchedFiles =
                findJavaFiles(
                        searchRoot,
                        normalizedClassName
                );

        if (matchedFiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "Java 클래스를 찾을 수 없습니다: "
                            + normalizedClassName
            );
        }

        if (matchedFiles.size() > 1) {
            throw new IllegalArgumentException(
                    buildMultipleClassMatchesContent(
                            workspaceRoot,
                            normalizedClassName,
                            matchedFiles
                    )
            );
        }

        return matchedFiles.getFirst()
                .toAbsolutePath()
                .normalize();
    }

    private Path getWorkspaceRoot() {
        return Path.of(
                        fileSystemProperties.getRootDirectory()
                )
                .toAbsolutePath()
                .normalize();
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
                            TRANSACTION_DIRECTORY_NAME
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
        if (
                requestedPath == null
                        || requestedPath.isBlank()
        ) {
            return ".";
        }

        return requestedPath;
    }

    private String buildMultipleClassMatchesContent(
            Path workspaceRoot,
            String className,
            List<Path> matchedFiles
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

        for (Path path : matchedFiles) {
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

    private void deleteDirectoryQuietly(
            Path directory
    ) {
        if (
                directory == null
                        || !Files.exists(
                        directory
                )
        ) {
            return;
        }

        try (
                Stream<Path> stream =
                        Files.walk(
                                directory
                        )
        ) {
            List<Path> paths =
                    stream.sorted(
                                    Comparator.reverseOrder()
                            )
                            .toList();

            for (Path path : paths) {
                Files.deleteIfExists(
                        path
                );
            }
        } catch (Exception exception) {
            log.warn(
                    "Code Edit Transaction 임시 폴더 삭제 실패. directory={}",
                    directory,
                    exception
            );
        }
    }

    public record CodeEditTransaction(
            String transactionId,
            Path transactionRoot,
            Map<Path, Path> snapshots
    ) {
    }
}