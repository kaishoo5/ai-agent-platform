package com.agent.aiagent.domain.file.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CodeFileChunkService {

    private static final int CHUNK_SIZE = 2_000;
    private static final int CHUNK_OVERLAP = 200;

    private static final String SOURCE_FILE_START =
            "===== SOURCE FILE =====";

    private static final String SOURCE_FILE_END =
            "===== END SOURCE FILE =====";

    public List<String> split(
            String content
    ) {
        if (
                content == null
                        || content.isBlank()
        ) {
            return List.of();
        }

        String normalizedContent =
                content.replaceAll(
                        "\\r\\n?",
                        "\n"
                );

        List<String> sourceFiles =
                splitSourceFiles(
                        normalizedContent
                );

        List<String> chunks =
                new ArrayList<>();

        for (String sourceFile : sourceFiles) {
            chunks.addAll(
                    splitSourceFile(
                            sourceFile
                    )
            );
        }

        return chunks;
    }

    private List<String> splitSourceFiles(
            String content
    ) {
        List<String> sourceFiles =
                new ArrayList<>();

        int searchIndex =
                0;

        while (searchIndex < content.length()) {
            int startIndex =
                    content.indexOf(
                            SOURCE_FILE_START,
                            searchIndex
                    );

            if (startIndex < 0) {
                break;
            }

            int endIndex =
                    content.indexOf(
                            SOURCE_FILE_END,
                            startIndex
                    );

            if (endIndex < 0) {
                break;
            }

            endIndex +=
                    SOURCE_FILE_END.length();

            String sourceFile =
                    content.substring(
                            startIndex,
                            endIndex
                    ).trim();

            if (!sourceFile.isBlank()) {
                sourceFiles.add(
                        sourceFile
                );
            }

            searchIndex =
                    endIndex;
        }

        return sourceFiles;
    }

    private List<String> splitSourceFile(
            String sourceFile
    ) {
        String path =
                extractMetadata(
                        sourceFile,
                        "PATH:"
                );

        String extension =
                extractMetadata(
                        sourceFile,
                        "EXTENSION:"
                );

        String code =
                extractCode(
                        sourceFile
                );

        if (code.isBlank()) {
            return List.of();
        }

        String header =
                createHeader(
                        path,
                        extension
                );

        int availableCodeSize =
                Math.max(
                        CHUNK_SIZE - header.length(),
                        500
                );

        if (
                code.length()
                        <= availableCodeSize
        ) {
            return List.of(
                    header + code
            );
        }

        List<String> chunks =
                new ArrayList<>();

        int startIndex =
                0;

        while (startIndex < code.length()) {
            int endIndex =
                    Math.min(
                            startIndex + availableCodeSize,
                            code.length()
                    );

            endIndex =
                    findBetterEndIndex(
                            code,
                            startIndex,
                            endIndex
                    );

            String codeChunk =
                    code.substring(
                            startIndex,
                            endIndex
                    ).trim();

            if (!codeChunk.isBlank()) {
                chunks.add(
                        header + codeChunk
                );
            }

            if (endIndex >= code.length()) {
                break;
            }

            startIndex =
                    Math.max(
                            endIndex - CHUNK_OVERLAP,
                            startIndex + 1
                    );
        }

        return chunks;
    }

    private int findBetterEndIndex(
            String code,
            int startIndex,
            int endIndex
    ) {
        if (endIndex >= code.length()) {
            return code.length();
        }

        int minimumEndIndex =
                Math.max(
                        startIndex + 500,
                        endIndex - 300
                );

        int lineBreakIndex =
                code.lastIndexOf(
                        '\n',
                        endIndex
                );

        if (
                lineBreakIndex
                        >= minimumEndIndex
        ) {
            return lineBreakIndex;
        }

        return endIndex;
    }

    private String extractMetadata(
            String sourceFile,
            String key
    ) {
        int keyIndex =
                sourceFile.indexOf(
                        key
                );

        if (keyIndex < 0) {
            return "";
        }

        int valueStartIndex =
                keyIndex + key.length();

        int valueEndIndex =
                sourceFile.indexOf(
                        '\n',
                        valueStartIndex
                );

        if (valueEndIndex < 0) {
            valueEndIndex =
                    sourceFile.length();
        }

        return sourceFile.substring(
                        valueStartIndex,
                        valueEndIndex
                )
                .trim();
    }

    private String extractCode(
            String sourceFile
    ) {
        int extensionIndex =
                sourceFile.indexOf(
                        "EXTENSION:"
                );

        if (extensionIndex < 0) {
            return "";
        }

        int codeStartIndex =
                sourceFile.indexOf(
                        '\n',
                        extensionIndex
                );

        if (codeStartIndex < 0) {
            return "";
        }

        codeStartIndex++;

        while (
                codeStartIndex < sourceFile.length()
                        && (
                        sourceFile.charAt(
                                codeStartIndex
                        ) == '\n'
                                || sourceFile.charAt(
                                codeStartIndex
                        ) == '\r'
                )
        ) {
            codeStartIndex++;
        }

        int codeEndIndex =
                sourceFile.lastIndexOf(
                        SOURCE_FILE_END
                );

        if (
                codeEndIndex < 0
                        || codeEndIndex
                        <= codeStartIndex
        ) {
            codeEndIndex =
                    sourceFile.length();
        }

        return sourceFile.substring(
                        codeStartIndex,
                        codeEndIndex
                )
                .trim();
    }

    private String createHeader(
            String path,
            String extension
    ) {
        return """
                SOURCE_PATH: %s
                SOURCE_EXTENSION: %s

                """.formatted(
                path,
                extension
        );
    }
}