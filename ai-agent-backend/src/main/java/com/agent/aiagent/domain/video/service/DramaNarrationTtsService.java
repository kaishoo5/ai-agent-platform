
package com.agent.aiagent.domain.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DramaNarrationTtsService {

    private static final String DEFAULT_VOICE = "ko-KR-SunHiNeural";
    private static final long TTS_TIMEOUT_MINUTES = 10;
    private static final long FFMPEG_TIMEOUT_MINUTES = 5;

    public NarrationAudio generate(String script, Path outputDirectory) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException(
                    "나레이션 대본이 비어 있습니다."
            );
        }

        if (outputDirectory == null) {
            throw new IllegalArgumentException(
                    "음성 출력 경로가 없습니다."
            );
        }

        try {
            Files.createDirectories(outputDirectory);

            String id = UUID.randomUUID().toString();

            Path mp3Path = outputDirectory.resolve(
                    "drama_narration_" + id + ".mp3"
            ).toAbsolutePath().normalize();

            Path wavPath = outputDirectory.resolve(
                    "drama_narration_" + id + ".wav"
            ).toAbsolutePath().normalize();

            log.info(
                    "드라마 나레이션 TTS 시작. scriptLength={}, voice={}",
                    script.length(),
                    DEFAULT_VOICE
            );

            try {
                generateMp3(script, mp3Path);
                convertToWav(mp3Path, wavPath);
            } catch (Exception exception) {
                Files.deleteIfExists(wavPath);
                throw exception;
            } finally {
                Files.deleteIfExists(mp3Path);
            }

            long durationMillis = getWavDurationMillis(wavPath);

            if (durationMillis <= 0) {
                Files.deleteIfExists(wavPath);
                throw new IllegalStateException(
                        "나레이션 음성 길이가 올바르지 않습니다."
                );
            }

            log.info(
                    "드라마 나레이션 TTS 완료. audioPath={}, durationMillis={}",
                    wavPath,
                    durationMillis
            );

            return new NarrationAudio(
                    wavPath,
                    durationMillis
            );

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "나레이션 음성 파일 생성 중 오류가 발생했습니다.",
                    exception
            );
        }
    }

    private void generateMp3(String script, Path outputPath)
            throws IOException {

        Path scriptFile = Files.createTempFile(
                "drama_narration_script_",
                ".txt"
        );

        try {
            Files.writeString(
                    scriptFile,
                    script,
                    StandardCharsets.UTF_8
            );

            List<String> command = List.of(
                    "py",
                    "-m",
                    "edge_tts",
                    "--voice",
                    DEFAULT_VOICE,
                    "--file",
                    scriptFile.toString(),
                    "--write-media",
                    outputPath.toString()
            );

            runProcess(
                    command,
                    TTS_TIMEOUT_MINUTES,
                    "Edge TTS"
            );

            validateOutput(outputPath);

        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    private void convertToWav(Path mp3Path, Path wavPath)
            throws IOException {

        List<String> command = List.of(
                "ffmpeg",
                "-y",
                "-i",
                mp3Path.toString(),
                "-vn",
                "-ac",
                "1",
                "-ar",
                "24000",
                "-c:a",
                "pcm_s16le",
                wavPath.toString()
        );

        runProcess(
                command,
                FFMPEG_TIMEOUT_MINUTES,
                "FFmpeg WAV 변환"
        );

        validateOutput(wavPath);
    }

    private long getWavDurationMillis(Path wavPath)
            throws IOException {

        long fileSize = Files.size(wavPath);

        // PCM 16bit / mono / 24000Hz
        long audioBytes = Math.max(0, fileSize - 44);
        long bytesPerSecond = 24000L * 2;

        return audioBytes * 1000 / bytesPerSecond;
    }

    private void validateOutput(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            throw new IllegalStateException(
                    "생성된 음성 파일이 없거나 비어 있습니다: " + path
            );
        }
    }

    private void runProcess(
            List<String> command,
            long timeoutMinutes,
            String taskName
    ) throws IOException {

        ProcessBuilder processBuilder = new ProcessBuilder(
                new ArrayList<>(command)
        );

        processBuilder.redirectErrorStream(true);

        Path logFile = Files.createTempFile(
                "drama_process_",
                ".log"
        );

        try {
            processBuilder.redirectOutput(logFile.toFile());

            Process process = processBuilder.start();

            boolean completed;

            try {
                completed = process.waitFor(
                        timeoutMinutes,
                        TimeUnit.MINUTES
                );

            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        taskName + " 실행이 중단되었습니다.",
                        exception
                );
            }

            if (!completed) {
                process.destroyForcibly();

                throw new IllegalStateException(
                        taskName + " 실행 시간이 초과되었습니다."
                );
            }

            if (process.exitValue() != 0) {
                String output = Files.readString(
                        logFile,
                        StandardCharsets.UTF_8
                );

                throw new IllegalStateException(
                        taskName + " 실행 실패. exitCode="
                                + process.exitValue()
                                + ", output="
                                + output.substring(
                                0,
                                Math.min(output.length(), 4000)
                        )
                );
            }

            log.info("{} 실행 완료.", taskName);

        } finally {
            Files.deleteIfExists(logFile);
        }
    }

    public record NarrationAudio(
            Path audioPath,
            long durationMillis
    ) {
    }
}
