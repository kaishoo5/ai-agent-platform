package com.agent.aiagent.domain.file.service;

import com.agent.aiagent.domain.file.model.FileAnalysisStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class FileAnalysisProgressService {

    private static final long SSE_TIMEOUT =
            30L * 60L * 1000L;

    private final ObjectMapper objectMapper;

    private final Map<String, List<FileAnalysisStep>> progressHistory =
            new ConcurrentHashMap<>();

    private final Map<String, List<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    public FileAnalysisProgressService(
            ObjectMapper objectMapper
    ) {
        this.objectMapper =
                objectMapper;
    }

    public SseEmitter subscribe(
            String fileId
    ) {
        SseEmitter emitter =
                new SseEmitter(
                        SSE_TIMEOUT
                );

        List<SseEmitter> fileEmitters =
                emitters.computeIfAbsent(
                        fileId,
                        ignored ->
                                new ArrayList<>()
                );

        synchronized (fileEmitters) {
            fileEmitters.add(
                    emitter
            );
        }

        emitter.onCompletion(() ->
                removeEmitter(
                        fileId,
                        emitter
                )
        );

        emitter.onTimeout(() -> {
            removeEmitter(
                    fileId,
                    emitter
            );

            emitter.complete();
        });

        emitter.onError(exception ->
                removeEmitter(
                        fileId,
                        emitter
                )
        );

        replayHistory(
                fileId,
                emitter
        );

        return emitter;
    }

    public void running(
            String fileId,
            String code,
            String message
    ) {
        publish(
                fileId,
                FileAnalysisStep.running(
                        code,
                        message
                )
        );
    }

    public void completed(
            String fileId,
            String code,
            String message
    ) {
        publish(
                fileId,
                FileAnalysisStep.completed(
                        code,
                        message
                )
        );
    }

    public void failed(
            String fileId,
            String code,
            String message
    ) {
        publish(
                fileId,
                FileAnalysisStep.failed(
                        code,
                        message
                )
        );
    }

    public List<FileAnalysisStep> getHistory(
            String fileId
    ) {
        List<FileAnalysisStep> history =
                progressHistory.get(
                        fileId
                );

        if (history == null) {
            return List.of();
        }

        synchronized (history) {
            return List.copyOf(
                    history
            );
        }
    }

    public void clear(
            String fileId
    ) {
        progressHistory.remove(
                fileId
        );
    }

    private void publish(
            String fileId,
            FileAnalysisStep step
    ) {
        addHistory(
                fileId,
                step
        );

        sendToSubscribers(
                fileId,
                step
        );
    }

    private void addHistory(
            String fileId,
            FileAnalysisStep step
    ) {
        List<FileAnalysisStep> history =
                progressHistory.computeIfAbsent(
                        fileId,
                        ignored ->
                                new ArrayList<>()
                );

        synchronized (history) {
            history.add(
                    step
            );
        }
    }

    private void replayHistory(
            String fileId,
            SseEmitter emitter
    ) {
        List<FileAnalysisStep> history =
                getHistory(
                        fileId
                );

        for (FileAnalysisStep step : history) {
            if (!send(
                    emitter,
                    step
            )) {
                removeEmitter(
                        fileId,
                        emitter
                );

                return;
            }
        }
    }

    private void sendToSubscribers(
            String fileId,
            FileAnalysisStep step
    ) {
        List<SseEmitter> fileEmitters =
                emitters.get(
                        fileId
                );

        if (fileEmitters == null) {
            return;
        }

        List<SseEmitter> snapshot;

        synchronized (fileEmitters) {
            snapshot =
                    List.copyOf(
                            fileEmitters
                    );
        }

        for (SseEmitter emitter : snapshot) {
            if (!send(
                    emitter,
                    step
            )) {
                removeEmitter(
                        fileId,
                        emitter
                );
            }
        }
    }

    private boolean send(
            SseEmitter emitter,
            FileAnalysisStep step
    ) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(
                                    "file_step"
                            )
                            .data(
                                    objectMapper.writeValueAsString(
                                            step
                                    )
                            )
            );

            return true;
        } catch (AsyncRequestNotUsableException exception) {
            return false;
        } catch (IOException exception) {
            return false;
        } catch (Exception exception) {
            log.error(
                    "파일 분석 진행 상태 SSE 전송 중 오류가 발생했습니다. code={}",
                    step.code(),
                    exception
            );

            return false;
        }
    }

    private void removeEmitter(
            String fileId,
            SseEmitter emitter
    ) {
        List<SseEmitter> fileEmitters =
                emitters.get(
                        fileId
                );

        if (fileEmitters == null) {
            return;
        }

        synchronized (fileEmitters) {
            fileEmitters.remove(
                    emitter
            );

            if (fileEmitters.isEmpty()) {
                emitters.remove(
                        fileId,
                        fileEmitters
                );
            }
        }
    }
}