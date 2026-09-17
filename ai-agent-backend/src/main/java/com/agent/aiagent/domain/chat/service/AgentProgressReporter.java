package com.agent.aiagent.domain.chat.service;

import com.agent.aiagent.domain.chat.model.AgentStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class AgentProgressReporter {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean terminated;

    public void running(
            String code,
            String message
    ) {
        send(
                AgentStep.running(
                        code,
                        message
                )
        );
    }

    public void completed(
            String code,
            String message
    ) {
        send(
                AgentStep.completed(
                        code,
                        message
                )
        );
    }

    private void send(
            AgentStep step
    ) {
        if (terminated.get()) {
            return;
        }

        try {
            emitter.send(
                    SseEmitter.event()
                            .name("agent_step")
                            .data(
                                    objectMapper.writeValueAsString(
                                            step
                                    )
                            )
            );
        } catch (
                AsyncRequestNotUsableException exception
        ) {
            terminated.set(true);

            log.info(
                    "Agent 진행 상태 전송 전에 클라이언트 연결이 종료되었습니다. code={}",
                    step.code()
            );
        } catch (
                IOException exception
        ) {
            terminated.set(true);

            log.info(
                    "Agent 진행 상태 SSE 전송 중 연결이 종료되었습니다. code={}",
                    step.code()
            );
        } catch (Exception exception) {
            terminated.set(true);

            log.error(
                    "Agent 진행 상태 SSE 전송 중 오류가 발생했습니다. code={}",
                    step.code(),
                    exception
            );

            emitter.completeWithError(
                    exception
            );
        }
    }
}