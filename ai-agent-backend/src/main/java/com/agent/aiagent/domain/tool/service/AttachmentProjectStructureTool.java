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

@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentProjectStructureTool implements AgentTool {

    private static final String SOURCE_PATH_PREFIX =
            "SOURCE_PATH:";

    private static final int MAX_TREE_DEPTH =
            8;

    private static final int MAX_TREE_ENTRIES =
            600;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "attachment_project_structure",
                    """
                    현재 대화에 첨부된 ZIP 프로젝트의 전체 파일/디렉터리 구조를 확인할 때 사용합니다.

                    프로젝트 전체 구조, 패키지 구성, 소스 루트, 주요 설정 파일, 빌드 파일,
                    프론트엔드/백엔드 디렉터리 구성처럼 프로젝트 전체 범위를 알아야 하는 질문에서는
                    attachment_search보다 먼저 이 도구를 사용하세요.

                    이 도구는 첨부 ZIP 분석 과정에서 저장된 SOURCE_PATH 정보를 사용하므로
                    로컬 작업 폴더를 스캔하지 않습니다.

                    특정 클래스의 구현 내용이나 특정 기능의 동작을 자세히 확인해야 할 때는
                    이 도구의 결과와 함께 attachment_search를 사용하세요.
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

            SortedSet<String> sourcePaths =
                    extractSourcePaths(
                            chunks
                    );

            if (sourcePaths.isEmpty()) {
                return ToolResult.failure(
                        "첨부파일에서 프로젝트 SOURCE_PATH 정보를 찾지 못했습니다."
                );
            }

            String content =
                    buildContent(
                            sourcePaths
                    );

            log.info(
                    "Attachment Project Structure Tool 실행 완료. "
                            + "roomId={}, fileCount={}, sourcePathCount={}",
                    context.roomId(),
                    context.fileIds().size(),
                    sourcePaths.size()
            );

            return ToolResult.success(
                    content,
                    Map.of(
                            "sourcePathCount",
                            sourcePaths.size()
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "Attachment Project Structure Tool 실행 실패. "
                            + "roomId={}, fileIds={}",
                    context.roomId(),
                    context.fileIds(),
                    exception
            );

            return ToolResult.failure(
                    "첨부 프로젝트 구조를 확인하는 중 오류가 발생했습니다."
            );
        }
    }

    private SortedSet<String> extractSourcePaths(
            List<ChatFileChunk> chunks
    ) {
        SortedSet<String> sourcePaths =
                new TreeSet<>();

        for (ChatFileChunk chunk : chunks) {
            String content =
                    chunk.getContent();

            if (
                    content == null
                            || content.isBlank()
            ) {
                continue;
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

                if (!sourcePath.isBlank()) {
                    sourcePaths.add(
                            sourcePath
                    );
                }

                break;
            }
        }

        return sourcePaths;
    }

    private String buildContent(
            SortedSet<String> sourcePaths
    ) {
        ProjectTreeNode root =
                new ProjectTreeNode();

        Map<String, Integer> extensionCounts =
                new TreeMap<>();

        List<String> importantFiles =
                new ArrayList<>();

        for (String sourcePath : sourcePaths) {
            addPath(
                    root,
                    sourcePath
            );

            String extension =
                    getExtension(
                            sourcePath
                    );

            extensionCounts.merge(
                    extension,
                    1,
                    Integer::sum
            );

            if (isImportantFile(sourcePath)) {
                importantFiles.add(
                        sourcePath
                );
            }
        }

        StringBuilder content =
                new StringBuilder();

        content.append("첨부 ZIP 프로젝트 구조\n\n");

        content.append("전체 소스 파일 수: ")
                .append(sourcePaths.size())
                .append("\n");

        content.append("파일 확장자 분포: ");

        appendExtensionCounts(
                content,
                extensionCounts
        );

        if (!importantFiles.isEmpty()) {
            content.append("\n\n## 주요 프로젝트 파일");

            for (String importantFile : importantFiles) {
                content.append("\n- `")
                        .append(importantFile)
                        .append("`");
            }
        }

        content.append("\n\n## 프로젝트 트리\n");

        int[] displayedEntries =
                new int[]{
                        0
                };

        appendTree(
                content,
                root,
                0,
                displayedEntries
        );

        if (displayedEntries[0] >= MAX_TREE_ENTRIES) {
            content.append("\n- ... 프로젝트 트리가 커서 최대 ")
                    .append(MAX_TREE_ENTRIES)
                    .append("개 항목까지만 표시했습니다.");
        }

        content.append("\n\n[최종 응답 작성 규칙]\n")
                .append("- 위 구조는 현재 첨부 ZIP에서 실제로 확인된 SOURCE_PATH만 기반으로 합니다.\n")
                .append("- 구조에 없는 프로젝트, 모듈, 프레임워크를 임의로 추가하지 마세요.\n")
                .append("- 특정 구현이나 기능 설명이 필요하면 attachment_search로 실제 코드를 추가 확인하세요.\n")
                .append("- 로컬 작업 폴더의 codebase_scan 결과와 혼합하지 마세요.");

        return content.toString()
                .trim();
    }

    private void addPath(
            ProjectTreeNode root,
            String sourcePath
    ) {
        String[] parts =
                Arrays.stream(
                                sourcePath.split("/")
                        )
                        .filter(
                                part -> !part.isBlank()
                        )
                        .toArray(
                                String[]::new
                        );

        ProjectTreeNode current =
                root;

        for (String part : parts) {
            current =
                    current.children
                            .computeIfAbsent(
                                    part,
                                    key ->
                                            new ProjectTreeNode()
                            );
        }
    }

    private void appendTree(
            StringBuilder content,
            ProjectTreeNode node,
            int depth,
            int[] displayedEntries
    ) {
        if (
                depth >= MAX_TREE_DEPTH
                        || displayedEntries[0] >= MAX_TREE_ENTRIES
        ) {
            return;
        }

        for (
                Map.Entry<String, ProjectTreeNode> entry
                : node.children.entrySet()
        ) {
            if (displayedEntries[0] >= MAX_TREE_ENTRIES) {
                return;
            }

            content.append(
                            "  ".repeat(
                                    depth
                            )
                    )
                    .append("- ")
                    .append(entry.getKey())
                    .append("\n");

            displayedEntries[0]++;

            appendTree(
                    content,
                    entry.getValue(),
                    depth + 1,
                    displayedEntries
            );
        }
    }

    private void appendExtensionCounts(
            StringBuilder content,
            Map<String, Integer> extensionCounts
    ) {
        boolean first =
                true;

        for (
                Map.Entry<String, Integer> entry
                : extensionCounts.entrySet()
        ) {
            if (!first) {
                content.append(", ");
            }

            content.append(entry.getKey())
                    .append("=")
                    .append(entry.getValue());

            first =
                    false;
        }
    }

    private String getExtension(
            String sourcePath
    ) {
        int slashIndex =
                sourcePath.lastIndexOf('/');

        int dotIndex =
                sourcePath.lastIndexOf('.');

        if (
                dotIndex < 0
                        || dotIndex < slashIndex
                        || dotIndex == sourcePath.length() - 1
        ) {
            return "(none)";
        }

        return sourcePath.substring(
                        dotIndex + 1
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private boolean isImportantFile(
            String sourcePath
    ) {
        String fileName =
                sourcePath.substring(
                                sourcePath.lastIndexOf('/') + 1
                        )
                        .toLowerCase(
                                Locale.ROOT
                        );

        return fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")
                || fileName.equals("settings.gradle")
                || fileName.equals("settings.gradle.kts")
                || fileName.equals("pom.xml")
                || fileName.equals("package.json")
                || fileName.equals("vite.config.js")
                || fileName.equals("vite.config.ts")
                || fileName.equals("nuxt.config.js")
                || fileName.equals("nuxt.config.ts")
                || fileName.equals("application.yml")
                || fileName.equals("application.yaml")
                || fileName.equals("application.properties")
                || fileName.startsWith("readme");
    }

    private static class ProjectTreeNode {

        private final Map<String, ProjectTreeNode> children =
                new TreeMap<>();
    }
}