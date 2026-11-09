package com.agent.aiagent.domain.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class SseHeartbeat {

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final ScheduledFuture<?> future;

    private SseHeartbeat(SseEmitter emitter, String roomId) {
        future = SCHEDULER.scheduleAtFixedRate(() -> {
            if (stopped.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException exception) {
                log.debug("SSE heartbeat 종료. roomId={}", roomId);
                stop();
            } catch (Exception exception) {
                log.warn("SSE heartbeat 전송 오류. roomId={}", roomId, exception);
                stop();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public static SseHeartbeat start(SseEmitter emitter, String roomId) {
        return new SseHeartbeat(emitter, roomId);
    }

    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            future.cancel(false);
        }
    }
}
