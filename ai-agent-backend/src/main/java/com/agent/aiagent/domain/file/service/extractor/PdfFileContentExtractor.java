package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
@Component
public class PdfFileContentExtractor implements FileContentExtractor {

    private static final int MAX_PAGE_COUNT = 100;

    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public String extract(ChatFile chatFile) {
        Path filePath =
                Path.of(chatFile.getStoredPath());

        try (
                PDDocument document =
                        Loader.loadPDF(filePath.toFile())
        ) {
            validateDocument(
                    chatFile,
                    document
            );

            PDFTextStripper textStripper =
                    new PDFTextStripper();

            int endPage =
                    Math.min(
                            document.getNumberOfPages(),
                            MAX_PAGE_COUNT
                    );

            textStripper.setStartPage(1);
            textStripper.setEndPage(endPage);
            textStripper.setSortByPosition(true);

            String content =
                    textStripper.getText(document);
            log.info(
                    "PDF 텍스트 추출 완료. fileId={}, length={}, content={}",
                    chatFile.getId(),
                    content.length(),
                    content
            );

            if (
                    document.getNumberOfPages()
                            > MAX_PAGE_COUNT
            ) {
                content +=
                        "\n\n...(PDF 페이지 제한으로 "
                                + MAX_PAGE_COUNT
                                + "페이지 이후 내용은 생략)...";
            }

            if (
                    content == null
                            || content.isBlank()
            ) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "PDF에서 텍스트를 추출할 수 없습니다. 이미지형 PDF일 수 있습니다."
                );
            }

            return content.trim();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            log.error(
                    "PDF 파일 읽기에 실패했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "PDF 파일이 손상되었거나 읽을 수 없습니다."
            );
        }
    }

    private void validateDocument(
            ChatFile chatFile,
            PDDocument document
    ) {
        if (document.isEncrypted()) {
            log.warn(
                    "암호화된 PDF 파일이 업로드되었습니다. fileId={}",
                    chatFile.getId()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "암호화된 PDF 파일은 분석할 수 없습니다."
            );
        }

        if (document.getNumberOfPages() == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "PDF 파일에 페이지가 없습니다."
            );
        }
    }
}