package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileContentExtractorManager {

    private final List<FileContentExtractor> extractors;

    public String extract(ChatFile chatFile) {
        String extension =
                chatFile.getExtension();

        log.info(
                "파일 추출기 선택 시작. fileId={}, fileName={}, extension={}",
                chatFile.getId(),
                chatFile.getOriginalName(),
                extension
        );

        FileContentExtractor extractor =
                extractors.stream()
                        .filter(item ->
                                item.supports(extension)
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "지원하지 않는 파일 형식입니다: "
                                                + extension
                                )
                        );

        log.info(
                "파일 추출기 선택 완료. fileId={}, extractor={}",
                chatFile.getId(),
                extractor.getClass().getSimpleName()
        );

        String content =
                extractor.extract(chatFile);

        log.info(
                "파일 내용 추출 완료. fileId={}, length={}",
                chatFile.getId(),
                content.length()
        );

        return content;
    }
}