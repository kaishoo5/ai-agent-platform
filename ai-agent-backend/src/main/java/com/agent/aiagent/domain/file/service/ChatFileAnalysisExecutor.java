package com.agent.aiagent.domain.file.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Slf4j
@Service
public class ChatFileAnalysisExecutor {

    private final ChatFileAnalysisService chatFileAnalysisService;
    private final FileAnalysisCancellationManager cancellationManager;
    private final ChatFileStatusService chatFileStatusService;
    private final FileAnalysisProgressService fileAnalysisProgressService;
    private final ExecutorService chatExecutor;

    private final ConcurrentHashMap<String, Boolean> runningTasks =
            new ConcurrentHashMap<>();

    public ChatFileAnalysisExecutor(
            ChatFileAnalysisService chatFileAnalysisService,
            FileAnalysisCancellationManager cancellationManager,
            ChatFileStatusService chatFileStatusService,
            FileAnalysisProgressService fileAnalysisProgressService,
            @Qualifier("chatExecutor") ExecutorService chatExecutor
    ) {
        this.chatFileAnalysisService = chatFileAnalysisService;
        this.cancellationManager = cancellationManager;
        this.chatFileStatusService = chatFileStatusService;
        this.fileAnalysisProgressService = fileAnalysisProgressService;
        this.chatExecutor = chatExecutor;
    }

    public void executeAfterCommit(
            String fileId
    ) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            execute(
                                    fileId
                            );
                        }
                    }
            );

            return;
        }

        execute(
                fileId
        );
    }

    public void cancel(
            String fileId
    ) {
        cancellationManager.cancel(
                fileId
        );

        if (!runningTasks.containsKey(fileId)) {
            log.info(
                    "실행 중인 파일 분석 작업이 없습니다. fileId={}",
                    fileId
            );
        }

        try {
            chatFileStatusService.markCancelled(
                    fileId
            );
        } catch (Exception exception) {
            log.warn(
                    "파일 분석 중단 상태 저장 실패. fileId={}",
                    fileId,
                    exception
            );
        }

        fileAnalysisProgressService.failed(
                fileId,
                "file_analysis",
                "파일 분석이 중단되었습니다."
        );

        log.info(
                "파일 분석 중단 요청 완료. fileId={}",
                fileId
        );
    }

    private void execute(
            String fileId
    ) {
        cancellationManager.begin(
                fileId
        );

        runningTasks.put(
                fileId,
                Boolean.TRUE
        );

        chatExecutor.execute(() -> {
            try {
                chatFileAnalysisService.analyze(
                        fileId
                );
            } catch (FileAnalysisCancelledException exception) {
                log.info(
                        "비동기 파일 분석이 사용자 요청으로 중단되었습니다. fileId={}",
                        fileId
                );
            } catch (Exception exception) {
                log.error(
                        "비동기 파일 분석 중 오류가 발생했습니다. fileId={}",
                        fileId,
                        exception
                );
            } finally {
                runningTasks.remove(
                        fileId
                );

                cancellationManager.finish(
                        fileId
                );
            }
        });
    }
}