package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.CodeBuildValidationResult;
import com.agent.aiagent.infra.filesystem.FileSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeBuildValidator {

    private static final String WINDOWS_GRADLE_WRAPPER =
            "gradlew.bat";

    private static final String UNIX_GRADLE_WRAPPER =
            "gradlew";

    private static final int MAX_SEARCH_DEPTH = 6;

    private final FileSystemProperties fileSystemProperties;

    public CodeBuildValidationResult validate() {

        Path workspaceRoot =
                getWorkspaceRoot();

        Path gradleWrapper =
                findGradleWrapper(
                        workspaceRoot
                );

        if (gradleWrapper == null) {
            return CodeBuildValidationResult.fail(
                    "Gradle Wrapper를 찾을 수 없습니다. workspaceRoot="
                            + workspaceRoot
            );
        }

        Path projectDirectory =
                gradleWrapper.getParent();

        log.info(
                "Code Build 검증 시작. projectDirectory={}",
                projectDirectory
        );

        ProcessBuilder processBuilder =
                new ProcessBuilder(
                        createCommand(
                                gradleWrapper
                        )
                );

        processBuilder.directory(
                projectDirectory.toFile()
        );

        processBuilder.redirectErrorStream(
                true
        );

        try {
            Process process =
                    processBuilder.start();

            String output =
                    readOutput(
                            process
                    );

            int exitCode =
                    process.waitFor();

            if (exitCode != 0) {

                log.warn(
                        "Code Build 검증 실패. exitCode={}, projectDirectory={}",
                        exitCode,
                        projectDirectory
                );

                return CodeBuildValidationResult.fail(
                        """
                        compileJava 실행에 실패했습니다.

                        exitCode: %d
                        projectDirectory: %s

                        %s
                        """.formatted(
                                exitCode,
                                projectDirectory,
                                output
                        )
                );
            }

            log.info(
                    "Code Build 검증 성공. projectDirectory={}",
                    projectDirectory
            );

            return CodeBuildValidationResult.success(
                    output
            );

        } catch (IOException exception) {

            log.error(
                    "Code Build 검증 실행 중 오류가 발생했습니다.",
                    exception
            );

            return CodeBuildValidationResult.fail(
                    "compileJava 실행 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            log.error(
                    "Code Build 검증 실행이 중단되었습니다.",
                    exception
            );

            return CodeBuildValidationResult.fail(
                    "compileJava 실행이 중단되었습니다."
            );
        }
    }

    private Path getWorkspaceRoot() {

        Path workspaceRoot =
                Path.of(
                                fileSystemProperties.getRootDirectory()
                        )
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(workspaceRoot)) {
            throw new IllegalStateException(
                    "Workspace Root가 존재하지 않습니다: "
                            + workspaceRoot
            );
        }

        return workspaceRoot;
    }

    private Path findGradleWrapper(
            Path workspaceRoot
    ) {

        try (
                Stream<Path> paths =
                        Files.walk(
                                workspaceRoot,
                                MAX_SEARCH_DEPTH
                        )
        ) {

            return paths
                    .filter(Files::isRegularFile)
                    .filter(this::isGradleWrapper)
                    .filter(this::hasBuildFile)
                    .findFirst()
                    .orElse(null);

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Gradle Wrapper 검색 중 오류가 발생했습니다.",
                    exception
            );
        }
    }

    private boolean isGradleWrapper(
            Path path
    ) {

        String fileName =
                path.getFileName()
                        .toString();

        return WINDOWS_GRADLE_WRAPPER.equalsIgnoreCase(
                fileName
        )
                || UNIX_GRADLE_WRAPPER.equals(
                fileName
        );
    }

    private boolean hasBuildFile(
            Path gradleWrapper
    ) {

        Path parent =
                gradleWrapper.getParent();

        if (parent == null) {
            return false;
        }

        return Files.exists(
                parent.resolve(
                        "build.gradle"
                )
        )
                || Files.exists(
                parent.resolve(
                        "build.gradle.kts"
                )
        );
    }

    private List<String> createCommand(
            Path gradleWrapper
    ) {

        boolean windows =
                System.getProperty(
                                "os.name"
                        )
                        .toLowerCase()
                        .contains(
                                "win"
                        );

        if (windows) {
            return List.of(
                    "cmd",
                    "/c",
                    gradleWrapper.toAbsolutePath()
                            .toString(),
                    "compileJava",
                    "--console=plain"
            );
        }

        return List.of(
                gradleWrapper.toAbsolutePath()
                        .toString(),
                "compileJava",
                "--console=plain"
        );
    }

    private String readOutput(
            Process process
    ) throws IOException {

        StringBuilder output =
                new StringBuilder();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;

            while (
                    (line = reader.readLine())
                            != null
            ) {

                output.append(
                        line
                );

                output.append(
                        System.lineSeparator()
                );
            }
        }

        return output.toString()
                .trim();
    }
}