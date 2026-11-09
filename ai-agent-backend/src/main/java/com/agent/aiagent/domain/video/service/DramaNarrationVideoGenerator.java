
package com.agent.aiagent.domain.video.service;

import com.agent.aiagent.domain.video.model.DramaEditPlan;
import com.agent.aiagent.domain.video.model.DramaSummaryMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaNarrationVideoGenerator {

    private final DramaStoryVideoGenerator storyVideoGenerator;
    private final DramaNarrationScriptService narrationScriptService;
    private final DramaNarrationTtsService narrationTtsService;

    public Path generate(DramaEditPlan plan) {
        if (plan == null
                || plan.segments() == null
                || plan.segments().isEmpty()) {
            throw new IllegalArgumentException(
                    "드라마 편집 계획이 없습니다."
            );
        }

        if (plan.mode() != DramaSummaryMode.NARRATION) {
            throw new IllegalArgumentException(
                    "NARRATION 모드 편집 계획만 지원합니다."
            );
        }

        DramaEditPlan storyPlan = new DramaEditPlan(
                DramaSummaryMode.STORY,
                plan.targetDurationMillis(),
                plan.plannedDurationMillis(),
                plan.segments()
        );

        Path storyVideo = null;
        Path output = null;

        List<Path> narrationAudios = new ArrayList<>();

        try {
            log.info(
                    "드라마 NARRATION 영상 생성 시작. segments={}",
                    plan.segments().size()
            );

            // 1. 기존 STORY 영상 생성 기능 재사용
            storyVideo = storyVideoGenerator.generate(storyPlan);

            // 2. 장면별 AI 대본 생성
            List<String> sceneScripts =
                    narrationScriptService.generateScenes(plan);

            if (sceneScripts.size() != plan.segments().size()) {
                throw new IllegalStateException(
                        "장면 수와 나레이션 대본 수가 일치하지 않습니다."
                );
            }

            // 3. 장면별 TTS 생성
            Path outputDirectory = storyVideo.getParent();

            List<Long> sceneStarts = new ArrayList<>();
            List<Long> sceneLengths = new ArrayList<>();

            long offsetMillis = 0L;

            for (int i = 0; i < sceneScripts.size(); i++) {
                long sceneDuration =
                        plan.segments().get(i).durationMillis();

                if (sceneDuration <= 0) {
                    throw new IllegalStateException(
                            "장면 길이가 올바르지 않습니다. scene=" + (i + 1)
                    );
                }

                DramaNarrationTtsService.NarrationAudio audio =
                        narrationTtsService.generate(
                                sceneScripts.get(i),
                                outputDirectory
                        );

                narrationAudios.add(audio.audioPath());
                sceneStarts.add(offsetMillis);
                sceneLengths.add(sceneDuration);

                log.info(
                        "장면 나레이션 준비 완료. scene={}/{}, startMillis={}, sceneMillis={}, audioMillis={}",
                        i + 1,
                        sceneScripts.size(),
                        offsetMillis,
                        sceneDuration,
                        audio.durationMillis()
                );

                if (audio.durationMillis() > sceneDuration) {
                    log.warn(
                            "장면 나레이션이 장면보다 길어 초과 음성이 잘립니다. scene={}, excessMillis={}",
                            i + 1,
                            audio.durationMillis() - sceneDuration
                    );
                }

                offsetMillis = Math.addExact(
                        offsetMillis,
                        sceneDuration
                );
            }

            // 4. 최종 출력 파일
            output = outputDirectory.resolve(
                    "drama_narration_" + UUID.randomUUID() + ".mp4"
            );

            // 5. 장면별 나레이션 동기화 합성
            mergeAudio(
                    storyVideo,
                    narrationAudios,
                    sceneStarts,
                    sceneLengths,
                    output
            );

            if (!Files.isRegularFile(output)
                    || Files.size(output) == 0) {
                throw new IllegalStateException(
                        "최종 나레이션 영상이 생성되지 않았습니다."
                );
            }

            log.info(
                    "드라마 NARRATION 영상 생성 완료. output={}, sceneCount={}, bytes={}",
                    output,
                    narrationAudios.size(),
                    Files.size(output)
            );

            return output;

        } catch (Exception exception) {
            deleteQuietly(output);

            throw new IllegalStateException(
                    "드라마 NARRATION 영상 생성 실패",
                    exception
            );

        } finally {
            deleteQuietly(storyVideo);

            for (Path audioPath : narrationAudios) {
                deleteQuietly(audioPath);
            }
        }
    }

    private void mergeAudio(
            Path videoPath,
            List<Path> narrationPaths,
            List<Long> sceneStarts,
            List<Long> sceneLengths,
            Path outputPath
    ) throws IOException, InterruptedException {

        if (narrationPaths.isEmpty()
                || narrationPaths.size() != sceneStarts.size()
                || narrationPaths.size() != sceneLengths.size()) {
            throw new IllegalArgumentException(
                    "장면별 오디오 정보가 올바르지 않습니다."
            );
        }

        List<String> command = new ArrayList<>(
                List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostdin",
                        "-y",
                        "-i",
                        videoPath.toString()
                )
        );

        // 장면별 TTS 파일을 FFmpeg 입력으로 등록
        for (Path narrationPath : narrationPaths) {
            command.add("-i");
            command.add(narrationPath.toString());
        }

        StringBuilder filter = new StringBuilder(
                "[0:a]volume=0.20[original];"
        );

        for (int i = 0; i < narrationPaths.size(); i++) {
            int inputIndex = i + 1;

            // 장면 길이를 초 단위로 변환
            String seconds = BigDecimal.valueOf(
                    sceneLengths.get(i),
                    3
            ).toPlainString();

            // 장면 길이까지만 사용하고
            // 해당 장면 시작 시간만큼 지연
            filter.append('[')
                    .append(inputIndex)
                    .append(":a]")
                    .append("atrim=duration=")
                    .append(seconds)
                    .append(",asetpts=PTS-STARTPTS")
                    .append(",adelay=")
                    .append(sceneStarts.get(i))
                    .append(":all=1")
                    .append("[n")
                    .append(i)
                    .append("];");
        }

        // 원본 오디오 + 모든 장면별 나레이션
        filter.append("[original]");

        for (int i = 0; i < narrationPaths.size(); i++) {
            filter.append("[n")
                    .append(i)
                    .append(']');
        }

        filter.append("amix=inputs=")
                .append(narrationPaths.size() + 1)
                .append(":duration=first")
                .append(":dropout_transition=0")
                .append(":normalize=0[aout]");

        command.addAll(
                List.of(
                        "-filter_complex",
                        filter.toString(),

                        "-map",
                        "0:v:0",

                        "-map",
                        "[aout]",

                        "-c:v",
                        "copy",

                        "-c:a",
                        "aac",

                        "-b:a",
                        "192k",

                        "-movflags",
                        "+faststart",

                        "-shortest",

                        outputPath.toString()
                )
        );

        Path logFile = Files.createTempFile(
                "drama_narration_ffmpeg_",
                ".log"
        );

        try {
            ProcessBuilder builder = new ProcessBuilder(command);

            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());

            Process process = builder.start();

            boolean completed = process.waitFor(
                    15,
                    TimeUnit.MINUTES
            );

            if (!completed) {
                process.destroyForcibly();

                throw new IllegalStateException(
                        "나레이션 영상 합성 시간이 초과되었습니다."
                );
            }

            if (process.exitValue() != 0) {
                String processOutput = Files.readString(
                        logFile,
                        StandardCharsets.UTF_8
                );

                throw new IllegalStateException(
                        "FFmpeg 나레이션 합성 실패. exitCode="
                                + process.exitValue()
                                + ", output="
                                + processOutput.substring(
                                0,
                                Math.min(4000, processOutput.length())
                        )
                );
            }

            log.info(
                    "장면별 나레이션 FFmpeg 합성 완료. sceneCount={}",
                    narrationPaths.size()
            );

        } finally {
            Files.deleteIfExists(logFile);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);

        } catch (IOException exception) {
            log.warn(
                    "임시 파일 삭제 실패. path={}",
                    path,
                    exception
            );
        }
    }
}
