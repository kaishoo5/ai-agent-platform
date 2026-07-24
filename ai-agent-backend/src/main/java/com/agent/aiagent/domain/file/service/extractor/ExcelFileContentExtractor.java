package com.agent.aiagent.domain.file.service.extractor;

import com.agent.aiagent.domain.file.entity.ChatFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class ExcelFileContentExtractor implements FileContentExtractor {

    private static final int MAX_SHEET_COUNT = 20;
    private static final int MAX_ROW_COUNT_PER_SHEET = 3_000;
    private static final int MAX_COLUMN_COUNT = 100;
    private static final int MAX_CELL_LENGTH = 2_000;

    @Override
    public boolean supports(String extension) {
        return "xlsx".equalsIgnoreCase(extension);
    }

    @Override
    public String extract(ChatFile chatFile) {
        Path filePath = Path.of(chatFile.getStoredPath());

        try (
                InputStream inputStream = Files.newInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(inputStream)
        ) {
            String content = extractWorkbook(workbook);

            if (content.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Excel 문서에서 데이터를 추출할 수 없습니다."
                );
            }

            log.info(
                    "XLSX 텍스트 추출 완료. fileId={}, sheetCount={}, length={}",
                    chatFile.getId(),
                    workbook.getNumberOfSheets(),
                    content.length()
            );

            return content;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            log.warn(
                    "암호화된 XLSX 파일입니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "암호화된 Excel 파일은 분석할 수 없습니다."
            );
        } catch (IOException exception) {
            log.error(
                    "XLSX 파일 읽기에 실패했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Excel 파일이 손상되었거나 읽을 수 없습니다."
            );
        } catch (RuntimeException exception) {
            log.error(
                    "XLSX 파일 분석 중 오류가 발생했습니다. fileId={}, storedPath={}",
                    chatFile.getId(),
                    chatFile.getStoredPath(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "올바른 XLSX 파일이 아닙니다."
            );
        }
    }

    private String extractWorkbook(Workbook workbook) {
        StringBuilder result = new StringBuilder();

        DataFormatter dataFormatter = new DataFormatter();
        FormulaEvaluator formulaEvaluator =
                workbook.getCreationHelper().createFormulaEvaluator();

        int sheetCount = Math.min(
                workbook.getNumberOfSheets(),
                MAX_SHEET_COUNT
        );

        for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);

            if (sheet.getPhysicalNumberOfRows() == 0) {
                continue;
            }

            appendSheet(
                    result,
                    sheet,
                    dataFormatter,
                    formulaEvaluator
            );
        }

        if (workbook.getNumberOfSheets() > MAX_SHEET_COUNT) {
            result.append("\n")
                    .append("[안내] 최대 ")
                    .append(MAX_SHEET_COUNT)
                    .append("개 시트까지만 분석했습니다.")
                    .append("\n");
        }

        return result.toString().trim();
    }

    private void appendSheet(
            StringBuilder result,
            Sheet sheet,
            DataFormatter dataFormatter,
            FormulaEvaluator formulaEvaluator
    ) {
        result.append("\n")
                .append("===== 시트: ")
                .append(sheet.getSheetName())
                .append(" =====")
                .append("\n");

        int firstRowNumber = sheet.getFirstRowNum();
        int lastRowNumber = Math.min(
                sheet.getLastRowNum(),
                firstRowNumber + MAX_ROW_COUNT_PER_SHEET - 1
        );

        int maxColumnCount = findMaxColumnCount(
                sheet,
                firstRowNumber,
                lastRowNumber
        );

        if (maxColumnCount == 0) {
            result.append("[빈 시트]\n");
            return;
        }

        for (
                int rowNumber = firstRowNumber;
                rowNumber <= lastRowNumber;
                rowNumber++
        ) {
            Row row = sheet.getRow(rowNumber);

            appendRow(
                    result,
                    row,
                    rowNumber,
                    maxColumnCount,
                    dataFormatter,
                    formulaEvaluator
            );
        }

        if (sheet.getLastRowNum() > lastRowNumber) {
            result.append("[안내] 이 시트는 최대 ")
                    .append(MAX_ROW_COUNT_PER_SHEET)
                    .append("행까지만 분석했습니다.")
                    .append("\n");
        }
    }

    private int findMaxColumnCount(
            Sheet sheet,
            int firstRowNumber,
            int lastRowNumber
    ) {
        int maxColumnCount = 0;

        for (
                int rowNumber = firstRowNumber;
                rowNumber <= lastRowNumber;
                rowNumber++
        ) {
            Row row = sheet.getRow(rowNumber);

            if (row == null || row.getLastCellNum() < 0) {
                continue;
            }

            maxColumnCount = Math.max(
                    maxColumnCount,
                    row.getLastCellNum()
            );
        }

        return Math.min(
                maxColumnCount,
                MAX_COLUMN_COUNT
        );
    }

    private void appendRow(
            StringBuilder result,
            Row row,
            int rowNumber,
            int maxColumnCount,
            DataFormatter dataFormatter,
            FormulaEvaluator formulaEvaluator
    ) {
        StringBuilder rowContent = new StringBuilder();
        boolean hasValue = false;

        for (
                int columnIndex = 0;
                columnIndex < maxColumnCount;
                columnIndex++
        ) {
            if (columnIndex > 0) {
                rowContent.append(" | ");
            }

            Cell cell = row == null
                    ? null
                    : row.getCell(
                    columnIndex,
                    Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
            );

            String value = formatCellValue(
                    cell,
                    dataFormatter,
                    formulaEvaluator
            );

            if (!value.isBlank()) {
                hasValue = true;
            }

            rowContent.append(value);
        }

        if (!hasValue) {
            return;
        }

        result.append("행 ")
                .append(rowNumber + 1)
                .append(": ")
                .append(rowContent)
                .append("\n");
    }

    private String formatCellValue(
            Cell cell,
            DataFormatter dataFormatter,
            FormulaEvaluator formulaEvaluator
    ) {
        if (cell == null) {
            return "";
        }

        String value;

        try {
            value = dataFormatter.formatCellValue(
                    cell,
                    formulaEvaluator
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "XLSX 셀 값 변환 실패. sheet={}, row={}, column={}",
                    cell.getSheet().getSheetName(),
                    cell.getRowIndex() + 1,
                    cell.getColumnIndex() + 1
            );

            value = dataFormatter.formatCellValue(cell);
        }

        value = normalizeCellValue(value);

        if (value.length() > MAX_CELL_LENGTH) {
            return value.substring(0, MAX_CELL_LENGTH)
                    + "... [셀 내용 생략]";
        }

        return value;
    }

    private String normalizeCellValue(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("|", "\\|")
                .trim();
    }
}