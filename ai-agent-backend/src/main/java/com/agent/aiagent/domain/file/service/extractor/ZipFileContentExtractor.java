package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
public class ZipFileContentExtractor implements FileContentExtractor {

    private static final long MAX_ENTRY_SIZE =
            2L * 1024L * 1024L;

    private static final long MAX_TOTAL_EXTRACTED_SIZE =
            20L * 1024L * 1024L;

    private static final int MAX_FILE_COUNT =
            1_000;

    private static final Set<String> SUPPORTED_SOURCE_EXTENSIONS =
            Set.of(
                    "java",
                    "kt",
                    "kts",
                    "js",
                    "jsx",
                    "ts",
                    "tsx",
                    "vue",
                    "html",
                    "css",
                    "scss",
                    "sass",
                    "less",
                    "json",
                    "xml",
                    "yaml",
                    "yml",
                    "properties",
                    "sql",
                    "md",
                    "txt",
                    "gradle"
            );

    private static final Set<String> IGNORED_DIRECTORY_NAMES =
            Set.of(
                    ".git",
                    ".idea",
                    ".gradle",
                    ".vscode",
                    "node_modules",
                    "target",
                    "build",
                    "dist",
                    "coverage",
                    ".next",
                    ".nuxt"
            );

    /*
     * 실제 애플리케이션 소스가 들어갈 가능성이 높은 디렉터리.
     *
     * 특정 프레임워크 하나에 종속시키지 않고
     * Java/Spring, Vue/Nuxt, React, 일반 JS/TS 프로젝트에서
     * 자주 사용하는 디렉터리를 포함한다.
     */
    private static final Set<String> HIGH_PRIORITY_DIRECTORY_NAMES =
            Set.of(
                    "src",
                    "app",
                    "pages",
                    "components",
                    "composables",
                    "stores",
                    "store",
                    "api",
                    "server",
                    "services",
                    "service",
                    "controllers",
                    "controller",
                    "domain",
                    "repository",
                    "repositories",
                    "config",
                    "configs",
                    "middleware",
                    "plugins",
                    "utils",
                    "util",
                    "hooks",
                    "router",
                    "routes"
            );

    /*
     * 프로젝트 이해에 중요한 루트/설정 파일.
     */
    private static final Set<String> HIGH_PRIORITY_FILE_NAMES =
            Set.of(
                    "package.json",
                    "pom.xml",
                    "build.gradle",
                    "build.gradle.kts",
                    "settings.gradle",
                    "settings.gradle.kts",
                    "gradle.properties",
                    "tsconfig.json",
                    "jsconfig.json",
                    "vite.config.js",
                    "vite.config.ts",
                    "nuxt.config.js",
                    "nuxt.config.ts",
                    "next.config.js",
                    "next.config.mjs",
                    "next.config.ts",
                    "vue.config.js",
                    "webpack.config.js",
                    "webpack.config.ts",
                    "application.properties",
                    "application.yml",
                    "application.yaml",
                    "docker-compose.yml",
                    "docker-compose.yaml",
                    "dockerfile"
            );

    /*
     * 프로젝트에 필요할 수는 있지만,
     * 실제 비즈니스 소스보다 우선해서 1000개 제한을
     * 차지하면 안 되는 디렉터리.
     */
    private static final Set<String> LOW_PRIORITY_DIRECTORY_NAMES =
            Set.of(
                    "public",
                    "static",
                    "assets",
                    "docs",
                    "doc",
                    "examples",
                    "example",
                    "samples",
                    "sample",
                    "sdk",
                    "vendor"
            );

    /*
     * 직접 작성한 소스보다 우선순위를 낮춰야 하는
     * 생성/압축/배포 성격의 파일명 패턴.
     */
    private static final List<String> LOW_PRIORITY_FILE_SUFFIXES =
            List.of(
                    ".min.js",
                    ".min.css",
                    ".bundle.js",
                    ".bundle.css"
            );

    @Override
    public boolean supports(
            String extension
    ) {
        return "zip".equalsIgnoreCase(
                extension
        );
    }

    @Override
    public String extract(
            ChatFile chatFile
    ) {
        Path zipPath =
                Path.of(
                        chatFile.getStoredPath()
                );

        /*
         * 1차:
         * ZIP 전체에서 분석 가능한 파일 경로만 수집한다.
         *
         * 여기서는 내용을 읽어 메모리에 올리지 않는다.
         */
        List<String> candidates =
                collectCandidatePaths(
                        zipPath,
                        chatFile
                );

        /*
         * 프로젝트 핵심 파일이 앞에 오도록 정렬한다.
         */
        candidates.sort(
                Comparator
                        .comparingInt(
                                this::getPriority
                        )
                        .thenComparingInt(
                                this::getPathDepth
                        )
                        .thenComparing(
                                String::compareToIgnoreCase
                        )
        );

        int candidateCount =
                candidates.size();

        /*
         * 실제 분석할 파일은 최대 1000개.
         */
        List<String> selectedPaths =
                candidates.stream()
                        .limit(
                                MAX_FILE_COUNT
                        )
                        .toList();

        if (candidateCount > MAX_FILE_COUNT) {
            log.warn(
                    "ZIP 분석 파일 개수 제한 적용. fileId={}, candidateCount={}, selectedCount={}, maxFileCount={}",
                    chatFile.getId(),
                    candidateCount,
                    selectedPaths.size(),
                    MAX_FILE_COUNT
            );
        }

        log.info(
                "ZIP 분석 대상 선정 완료. fileId={}, candidateCount={}, selectedCount={}",
                chatFile.getId(),
                candidateCount,
                selectedPaths.size()
        );

        /*
         * HashSet으로 바꿔 두 번째 ZIP 순회 시
         * 빠르게 포함 여부를 확인한다.
         */
        Set<String> selectedPathSet =
                new HashSet<>(
                        selectedPaths
                );

        StringBuilder content =
                new StringBuilder();

        long totalExtractedSize =
                0L;

        int fileCount =
                0;

        /*
         * 2차:
         * 선정된 파일만 실제로 읽는다.
         */
        try (
                InputStream inputStream =
                        Files.newInputStream(
                                zipPath
                        );

                ZipInputStream zipInputStream =
                        new ZipInputStream(
                                inputStream,
                                StandardCharsets.UTF_8
                        )
        ) {
            ZipEntry entry;

            while (
                    (entry = zipInputStream.getNextEntry())
                            != null
            ) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();

                    continue;
                }

                String entryName =
                        normalizeEntryName(
                                entry.getName()
                        );

                if (
                        entryName == null
                                || !selectedPathSet.contains(
                                entryName
                        )
                ) {
                    zipInputStream.closeEntry();

                    continue;
                }

                byte[] fileBytes =
                        readEntry(
                                zipInputStream
                        );

                if (fileBytes.length == 0) {
                    zipInputStream.closeEntry();

                    continue;
                }

                totalExtractedSize +=
                        fileBytes.length;

                if (
                        totalExtractedSize
                                > MAX_TOTAL_EXTRACTED_SIZE
                ) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "ZIP 내부 소스 파일의 전체 크기가 너무 큽니다."
                    );
                }

                String fileContent =
                        new String(
                                fileBytes,
                                StandardCharsets.UTF_8
                        );

                if (fileContent.isBlank()) {
                    zipInputStream.closeEntry();

                    continue;
                }

                appendSourceFile(
                        content,
                        entryName,
                        fileContent
                );

                fileCount++;

                zipInputStream.closeEntry();
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            log.error(
                    "ZIP 파일 분석에 실패했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "ZIP 파일을 분석할 수 없습니다."
            );
        }

        log.info(
                "ZIP 소스 추출 완료. fileId={}, fileName={}, candidateCount={}, selectedCount={}, sourceFileCount={}, extractedSize={}",
                chatFile.getId(),
                chatFile.getOriginalName(),
                candidateCount,
                selectedPaths.size(),
                fileCount,
                totalExtractedSize
        );

        return content.toString()
                .trim();
    }

    /*
     * ZIP 전체를 한 번 순회해서
     * 분석 가능한 파일 경로만 수집한다.
     *
     * 파일 내용은 읽지 않기 때문에
     * 대형 프로젝트에서도 메모리 사용량이 작다.
     */
    private List<String> collectCandidatePaths(
            Path zipPath,
            ChatFile chatFile
    ) {
        List<String> candidates =
                new ArrayList<>();

        try (
                InputStream inputStream =
                        Files.newInputStream(
                                zipPath
                        );

                ZipInputStream zipInputStream =
                        new ZipInputStream(
                                inputStream,
                                StandardCharsets.UTF_8
                        )
        ) {
            ZipEntry entry;

            while (
                    (entry = zipInputStream.getNextEntry())
                            != null
            ) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();

                    continue;
                }

                String entryName =
                        normalizeEntryName(
                                entry.getName()
                        );

                if (
                        entryName == null
                                || shouldIgnore(
                                entryName
                        )
                                || !isSupportedSourceFile(
                                entryName
                        )
                ) {
                    zipInputStream.closeEntry();

                    continue;
                }

                candidates.add(
                        entryName
                );

                zipInputStream.closeEntry();
            }
        } catch (IOException exception) {
            log.error(
                    "ZIP 파일 목록 분석에 실패했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "ZIP 파일의 파일 목록을 분석할 수 없습니다."
            );
        }

        return candidates;
    }

    /*
     * 숫자가 작을수록 우선순위가 높다.
     *
     * 0 = 프로젝트 핵심 설정 파일
     * 1 = 실제 애플리케이션 소스
     * 2 = 일반 소스
     * 3 = public/static/assets 등
     * 4 = minified/bundle/vendor/sdk 등
     */
    private int getPriority(
            String entryName
    ) {
        String normalized =
                entryName.toLowerCase();

        String fileName =
                getFileName(
                        normalized
                );

        if (
                HIGH_PRIORITY_FILE_NAMES.contains(
                        fileName
                )
                        || isApplicationConfigFile(
                        fileName
                )
        ) {
            return 0;
        }

        if (
                isLowPriorityFile(
                        normalized
                )
        ) {
            return 4;
        }

        if (
                containsPathPart(
                        normalized,
                        "vendor"
                )
                        || containsPathPart(
                        normalized,
                        "sdk"
                )
        ) {
            return 4;
        }

        if (
                containsAnyPathPart(
                        normalized,
                        HIGH_PRIORITY_DIRECTORY_NAMES
                )
        ) {
            return 1;
        }

        if (
                containsAnyPathPart(
                        normalized,
                        LOW_PRIORITY_DIRECTORY_NAMES
                )
        ) {
            return 3;
        }

        return 2;
    }

    private boolean isApplicationConfigFile(
            String fileName
    ) {
        return fileName.startsWith(
                "application-"
        )
                && (
                fileName.endsWith(
                        ".properties"
                )
                        || fileName.endsWith(
                        ".yml"
                )
                        || fileName.endsWith(
                        ".yaml"
                )
        );
    }

    private boolean isLowPriorityFile(
            String entryName
    ) {
        for (
                String suffix
                : LOW_PRIORITY_FILE_SUFFIXES
        ) {
            if (
                    entryName.endsWith(
                            suffix
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private boolean containsAnyPathPart(
            String entryName,
            Set<String> directoryNames
    ) {
        String[] pathParts =
                entryName.split(
                        "/"
                );

        /*
         * 마지막 값은 파일명이므로 검사하지 않는다.
         */
        for (
                int index = 0;
                index < pathParts.length - 1;
                index++
        ) {
            if (
                    directoryNames.contains(
                            pathParts[index]
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private boolean containsPathPart(
            String entryName,
            String directoryName
    ) {
        String[] pathParts =
                entryName.split(
                        "/"
                );

        for (
                int index = 0;
                index < pathParts.length - 1;
                index++
        ) {
            if (
                    directoryName.equals(
                            pathParts[index]
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private int getPathDepth(
            String entryName
    ) {
        int depth =
                0;

        for (
                int index = 0;
                index < entryName.length();
                index++
        ) {
            if (
                    entryName.charAt(
                            index
                    ) == '/'
            ) {
                depth++;
            }
        }

        return depth;
    }

    private String getFileName(
            String entryName
    ) {
        int index =
                entryName.lastIndexOf(
                        "/"
                );

        if (index < 0) {
            return entryName;
        }

        return entryName.substring(
                index + 1
        );
    }

    private byte[] readEntry(
            ZipInputStream zipInputStream
    ) throws IOException {
        byte[] buffer =
                new byte[8_192];

        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        int readLength;

        while (
                (readLength = zipInputStream.read(buffer))
                        != -1
        ) {
            if (
                    outputStream.size()
                            + readLength
                            > MAX_ENTRY_SIZE
            ) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "ZIP 내부에 너무 큰 소스 파일이 있습니다."
                );
            }

            outputStream.write(
                    buffer,
                    0,
                    readLength
            );
        }

        return outputStream.toByteArray();
    }

    private void appendSourceFile(
            StringBuilder content,
            String filePath,
            String fileContent
    ) {
        if (!content.isEmpty()) {
            content.append(
                    "\n\n"
            );
        }

        content.append(
                "===== SOURCE FILE =====\n"
        );

        content.append(
                "PATH: "
        );

        content.append(
                filePath
        );

        content.append(
                "\n"
        );

        content.append(
                "EXTENSION: "
        );

        content.append(
                getExtension(
                        filePath
                )
        );

        content.append(
                "\n\n"
        );

        content.append(
                fileContent
        );

        content.append(
                "\n===== END SOURCE FILE ====="
        );
    }

    private boolean shouldIgnore(
            String entryName
    ) {
        String[] pathParts =
                entryName.split(
                        "/"
                );

        for (String pathPart : pathParts) {
            if (
                    IGNORED_DIRECTORY_NAMES.contains(
                            pathPart
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private boolean isSupportedSourceFile(
            String entryName
    ) {
        String extension =
                getExtension(
                        entryName
                );

        return SUPPORTED_SOURCE_EXTENSIONS.contains(
                extension
        );
    }

    private String getExtension(
            String fileName
    ) {
        int index =
                fileName.lastIndexOf(
                        "."
                );

        if (
                index < 0
                        || index
                        == fileName.length() - 1
        ) {
            return "";
        }

        return fileName
                .substring(
                        index + 1
                )
                .toLowerCase();
    }

    private String normalizeEntryName(
            String entryName
    ) {
        if (
                entryName == null
                        || entryName.isBlank()
        ) {
            return null;
        }

        String normalized =
                entryName
                        .replace(
                                '\\',
                                '/'
                        )
                        .trim();

        if (
                normalized.startsWith("/")
                        || normalized.contains("../")
                        || normalized.equals("..")
        ) {
            return null;
        }

        return normalized;
    }
}