package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class DocxFileContentExtractor implements FileContentExtractor {

    @Override
    public boolean supports(String extension) {
        return "docx".equalsIgnoreCase(extension);
    }

    @Override
    public String extract(ChatFile chatFile) {
        Path filePath =
                Path.of(chatFile.getStoredPath());

        try (
                InputStream inputStream =
                        Files.newInputStream(filePath);

                XWPFDocument document =
                        new XWPFDocument(inputStream);

                XWPFWordExtractor extractor =
                        new XWPFWordExtractor(document)
        ) {
            String content =
                    extractor.getText();

            if (
                    content == null
                            || content.isBlank()
            ) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Word 문서에서 텍스트를 추출할 수 없습니다."
                );
            }

            log.info(
                    "DOCX 텍스트 추출 완료. fileId={}, length={}",
                    chatFile.getId(),
                    content.length()
            );

            return content.trim();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            log.error(
                    "DOCX 파일 읽기에 실패했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Word 파일이 손상되었거나 읽을 수 없습니다."
            );
        } catch (RuntimeException exception) {
            log.error(
                    "DOCX 파일 분석 중 오류가 발생했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "올바른 DOCX 파일이 아닙니다."
            );
        }
    }
}