package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.file.entity.ChatFile;
import com.agent.aiagent.domain.file.repository.ChatFileRepository;
import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaEditSegment;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaStoryVideoGenerator {

    private final ChatFileRepository chatFileRepository;

    /** STORY 편집 계획에 따라 원본 클립을 추출하고 하나의 MP4로 병합한다. */
    public Path generate(DramaEditPlan plan) {
        if (plan == null || plan.mode() != DramaSummaryMode.STORY) {
            throw new IllegalArgumentException("STORY 모드 편집 계획만 지원합니다.");
        }
        if (plan.segments() == null || plan.segments().isEmpty()) {
            throw new IllegalArgumentException("편집 구간이 없습니다.");
        }

        List<DramaEditSegment> segments = plan.segments().stream()
                .sorted(Comparator.comparingInt(DramaEditSegment::episodeNumber)
                        .thenComparingLong(DramaEditSegment::startMillis))
                .toList();

        for (DramaEditSegment segment : segments) {
            if (segment.fileId() == null || segment.fileId().isBlank()
                    || segment.startMillis() < 0 || segment.endMillis() <= segment.startMillis()) {
                throw new IllegalArgumentException("잘못된 편집 구간: " + segment);
            }
        }

        Map<String, Path> sourcePaths = segments.stream()
                .map(DramaEditSegment::fileId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::findVideoPath));

        Path outputDirectory = sourcePaths.get(segments.get(0).fileId()).getParent();
        Path workDirectory;
        try {
            workDirectory = Files.createTempDirectory(outputDirectory, "drama-render-");
        } catch (IOException e) {
            throw new IllegalStateException("임시 작업 폴더를 생성하지 못했습니다.", e);
        }

        Path output = outputDirectory.resolve("drama_story_" + UUID.randomUUID() + ".mp4");
        try {
            List<Path> clips = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                DramaEditSegment segment = segments.get(i);
                Path clip = workDirectory.resolve("clip_%04d.mp4".formatted(i));
                List<String> command = new ArrayList<>(List.of(
                        "ffmpeg", "-hide_banner", "-nostdin", "-y",
                        "-ss", String.valueOf(segment.startMillis() / 1000.0),
                        "-i", sourcePaths.get(segment.fileId()).toString(),
                        "-t", String.valueOf(segment.durationMillis() / 1000.0),
                        "-map", "0:v:0", "-map", "0:a:0",
                        "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30",
                        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-ar", "48000", "-ac", "2",
                        "-avoid_negative_ts", "make_zero", clip.toString()
                ));
                log.info("드라마 클립 렌더링. index={}, episode={}, durationMillis={}",
                        i + 1, segment.episodeNumber(), segment.durationMillis());
                execute(command);
                clips.add(clip);
            }

            Path concatList = workDirectory.resolve("concat.txt");
            List<String> lines = clips.stream()
                    .map(path -> "file '" + path.getFileName().toString() + "'")
                    .toList();
            Files.write(concatList, lines, StandardCharsets.UTF_8);
            execute(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y",
                    "-f", "concat", "-safe", "0", "-i", concatList.toString(),
                    "-c", "copy", "-movflags", "+faststart", output.toString()));

            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IllegalStateException("최종 드라마 요약 영상이 생성되지 않았습니다.");
            }
            log.info("드라마 STORY 영상 생성 완료. output={}, clips={}, bytes={}",
                    output, clips.size(), Files.size(output));
            return output;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(output);
            } catch (IOException cleanupError) {
                log.warn("불완전한 출력 파일 삭제 실패: {}", output, cleanupError);
            }
            throw new IllegalStateException("드라마 STORY 영상 생성 실패", e);
        } finally {
            try (var files = Files.walk(workDirectory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException cleanupError) {
                log.warn("임시 파일 정리 실패: {}", workDirectory, cleanupError);
            }
        }
    }

    private Path findVideoPath(String fileId) {
        ChatFile file = chatFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("파일 정보 없음: " + fileId));
        if (file.getStoredPath() == null || file.getStoredPath().isBlank()) {
            throw new IllegalStateException("영상 경로 없음: " + fileId);
        }
        Path path = Path.of(file.getStoredPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("영상 파일 없음: " + path);
        }
        return path;
    }

    private void execute(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("FFmpeg 종료 코드=" + code + "\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg 실행 중단", e);
        } catch (IOException e) {
            throw new IllegalStateException("FFmpeg 실행 오류", e);
        }
    }
}
