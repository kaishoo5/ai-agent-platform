package com.agent.aiagent.domain.file.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FileAnalysisCancellationManager {

    private final Set<String> cancelledFileIds =
            ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, Process> runningProcesses =
            new ConcurrentHashMap<>();

    public void begin(
            String fileId
    ) {
        cancelledFileIds.remove(
                fileId
        );
    }

    public void cancel(
            String fileId
    ) {
        cancelledFileIds.add(
                fileId
        );

        Process process =
                runningProcesses.get(
                        fileId
                );

        destroyProcess(
                process
        );
    }

    public void checkCancelled(
            String fileId
    ) {
        if (
                Thread.currentThread().isInterrupted()
                        || cancelledFileIds.contains(
                        fileId
                )
        ) {
            throw new FileAnalysisCancelledException(
                    fileId
            );
        }
    }

    public void registerProcess(
            String fileId,
            Process process
    ) {
        checkCancelled(
                fileId
        );

        runningProcesses.put(
                fileId,
                process
        );

        if (
                cancelledFileIds.contains(
                        fileId
                )
        ) {
            destroyProcess(
                    process
            );

            throw new FileAnalysisCancelledException(
                    fileId
            );
        }
    }

    public void unregisterProcess(
            String fileId,
            Process process
    ) {
        runningProcesses.remove(
                fileId,
                process
        );
    }

    public void finish(
            String fileId
    ) {
        runningProcesses.remove(
                fileId
        );

        cancelledFileIds.remove(
                fileId
        );
    }

    private void destroyProcess(
            Process process
    ) {
        if (
                process == null
                        || !process.isAlive()
        ) {
            return;
        }

        process.descendants()
                .forEach(ProcessHandle::destroyForcibly);

        process.destroyForcibly();
    }
}
