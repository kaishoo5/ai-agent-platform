package com.agent.aiagent.domain.file.service;

public class FileAnalysisCancelledException extends RuntimeException {

    public FileAnalysisCancelledException(
            String fileId
    ) {
        super(
                "파일 분석이 중단되었습니다. fileId="
                        + fileId
        );
    }

    public FileAnalysisCancelledException(
            String fileId,
            Throwable cause
    ) {
        super(
                "파일 분석이 중단되었습니다. fileId="
                        + fileId,
                cause
        );
    }
}
