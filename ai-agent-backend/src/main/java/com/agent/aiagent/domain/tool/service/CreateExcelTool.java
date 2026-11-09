package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.file.entity.GeneratedFile;
import com.agent.aiagent.domain.file.service.GeneratedFileService;
import com.agent.aiagent.domain.tool.model.ToolExecutionContext;
import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateExcelTool implements AgentTool {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final int MAX_ROW_COUNT =
            10_000;

    private static final int MAX_COLUMN_COUNT =
            100;

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "create_excel",
                    """
                    표 형태의 데이터를 Excel(.xlsx) 파일로 생성합니다.
                    headers에는 열 이름을, rows에는 각 행의 데이터를 전달합니다.
                    rows의 각 행은 headers와 동일한 순서의 값 배열이어야 합니다.
                    사용자가 표, 목록, 정리된 데이터를 Excel 파일로 만들어 달라고 요청할 때 사용합니다.
                    """,
                    Map.of(
                            "fileName",
                            new ToolParameter(
                                    "string",
                                    "생성할 Excel 파일명입니다. 예: employees.xlsx",
                                    true
                            ),
                            "sheetName",
                            new ToolParameter(
                                    "string",
                                    "Excel 시트 이름입니다. 예: 직원목록",
                                    true
                            ),
                            "headers",
                            new ToolParameter(
                                    "array",
                                    "Excel 첫 번째 행에 작성할 열 이름 배열입니다. 예: [\"이름\", \"부서\", \"직급\"]",
                                    true
                            ),
                            "rows",
                            new ToolParameter(
                                    "array",
                                    "Excel 데이터 행의 2차원 배열입니다. 예: [[\"홍길동\", \"개발팀\", \"과장\"], [\"김철수\", \"기획팀\", \"대리\"]]",
                                    true
                            )
                    )
            );

    private final GeneratedFileService generatedFileService;

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        return ToolResult.failure(
                "Excel 파일 생성에는 채팅 실행 컨텍스트가 필요합니다."
        );
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments,
            ToolExecutionContext context
    ) {
        if (
                context == null
                        || context.roomId() == null
                        || context.roomId().isBlank()
        ) {
            return ToolResult.failure(
                    "채팅방 정보를 확인할 수 없습니다."
            );
        }

        String fileName =
                getRequiredString(
                        arguments,
                        "fileName"
                );

        String sheetName =
                getRequiredString(
                        arguments,
                        "sheetName"
                );

        List<String> headers =
                getStringList(
                        arguments,
                        "headers"
                );

        List<List<Object>> rows =
                getRows(
                        arguments,
                        "rows"
                );

        if (fileName == null) {
            return ToolResult.failure(
                    "생성할 Excel 파일명이 없습니다."
            );
        }

        if (sheetName == null) {
            return ToolResult.failure(
                    "Excel 시트 이름이 없습니다."
            );
        }

        if (headers == null || headers.isEmpty()) {
            return ToolResult.failure(
                    "Excel 헤더 정보가 없습니다."
            );
        }

        if (rows == null) {
            return ToolResult.failure(
                    "Excel 데이터 행 정보가 없습니다."
            );
        }

        if (headers.size() > MAX_COLUMN_COUNT) {
            return ToolResult.failure(
                    "Excel 열은 최대 "
                            + MAX_COLUMN_COUNT
                            + "개까지 생성할 수 있습니다."
            );
        }

        if (rows.size() > MAX_ROW_COUNT) {
            return ToolResult.failure(
                    "Excel 데이터는 최대 "
                            + MAX_ROW_COUNT
                            + "행까지 생성할 수 있습니다."
            );
        }

        String normalizedFileName =
                normalizeFileName(
                        fileName
                );

        String normalizedSheetName =
                normalizeSheetName(
                        sheetName
                );

        try (
                Workbook workbook =
                        new XSSFWorkbook();

                ByteArrayOutputStream outputStream =
                        new ByteArrayOutputStream()
        ) {
            Sheet sheet =
                    workbook.createSheet(
                            normalizedSheetName
                    );

            CellStyle headerStyle =
                    createHeaderStyle(
                            workbook
                    );

            createHeaderRow(
                    sheet,
                    headers,
                    headerStyle
            );

            createDataRows(
                    sheet,
                    headers.size(),
                    rows
            );

            autoSizeColumns(
                    sheet,
                    headers.size()
            );

            workbook.write(
                    outputStream
            );

            byte[] content =
                    outputStream.toByteArray();

            GeneratedFile generatedFile =
                    generatedFileService.save(
                            context.roomId(),
                            normalizedFileName,
                            content,
                            CONTENT_TYPE
                    );

            log.info(
                    "Create Excel Tool 실행 완료. roomId={}, fileId={}, fileName={}, rowCount={}, columnCount={}, size={}",
                    context.roomId(),
                    generatedFile.getId(),
                    generatedFile.getOriginalName(),
                    rows.size(),
                    headers.size(),
                    generatedFile.getSize()
            );

            return ToolResult.success(
                    new StringBuilder()
                            .append("Excel 파일을 성공적으로 생성했습니다.")
                            .append("\n")
                            .append("파일명: ")
                            .append(
                                    generatedFile.getOriginalName()
                            )
                            .append("\n")
                            .append("행 수: ")
                            .append(
                                    rows.size()
                            )
                            .append("\n")
                            .append("열 수: ")
                            .append(
                                    headers.size()
                            )
                            .append("\n")
                            .append("최종 답변에서는 파일 생성 완료 사실과 파일명만 자연스럽게 안내하세요.")
                            .append("\n")
                            .append("파일 ID, 내부 저장 정보, 다운로드 방식에 대한 설명은 답변에 포함하지 마세요.")
                            .toString(),
                    Map.of(
                            "generatedFileId",
                            generatedFile.getId(),
                            "fileName",
                            generatedFile.getOriginalName(),
                            "contentType",
                            CONTENT_TYPE,
                            "extension",
                            generatedFile.getExtension(),
                            "size",
                            generatedFile.getSize()
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "Create Excel Tool 실행 실패. roomId={}, fileName={}",
                    context.roomId(),
                    normalizedFileName,
                    exception
            );

            return ToolResult.failure(
                    "Excel 파일을 생성하는 중 오류가 발생했습니다."
            );
        }
    }

    private CellStyle createHeaderStyle(
            Workbook workbook
    ) {
        Font font =
                workbook.createFont();

        font.setBold(
                true
        );

        CellStyle style =
                workbook.createCellStyle();

        style.setFont(
                font
        );

        return style;
    }

    private void createHeaderRow(
            Sheet sheet,
            List<String> headers,
            CellStyle headerStyle
    ) {
        Row headerRow =
                sheet.createRow(
                        0
                );

        for (
                int columnIndex = 0;
                columnIndex < headers.size();
                columnIndex++
        ) {
            Cell cell =
                    headerRow.createCell(
                            columnIndex
                    );

            cell.setCellValue(
                    headers.get(
                            columnIndex
                    )
            );

            cell.setCellStyle(
                    headerStyle
            );
        }
    }

    private void createDataRows(
            Sheet sheet,
            int columnCount,
            List<List<Object>> rows
    ) {
        for (
                int rowIndex = 0;
                rowIndex < rows.size();
                rowIndex++
        ) {
            Row excelRow =
                    sheet.createRow(
                            rowIndex + 1
                    );

            List<Object> values =
                    rows.get(
                            rowIndex
                    );

            for (
                    int columnIndex = 0;
                    columnIndex < columnCount;
                    columnIndex++
            ) {
                Object value =
                        columnIndex < values.size()
                                ? values.get(
                                columnIndex
                        )
                                : null;

                Cell cell =
                        excelRow.createCell(
                                columnIndex
                        );

                setCellValue(
                        cell,
                        value
                );
            }
        }
    }

    private void setCellValue(
            Cell cell,
            Object value
    ) {
        if (value == null) {
            cell.setBlank();
            return;
        }

        if (value instanceof Number number) {
            cell.setCellValue(
                    number.doubleValue()
            );
            return;
        }

        if (value instanceof Boolean booleanValue) {
            cell.setCellValue(
                    booleanValue
            );
            return;
        }

        cell.setCellValue(
                value.toString()
        );
    }

    private void autoSizeColumns(
            Sheet sheet,
            int columnCount
    ) {
        for (
                int columnIndex = 0;
                columnIndex < columnCount;
                columnIndex++
        ) {
            sheet.autoSizeColumn(
                    columnIndex
            );
        }
    }

    private String normalizeFileName(
            String fileName
    ) {
        String normalized =
                fileName.trim();

        normalized =
                normalized
                        .replace(
                                "\\",
                                "_"
                        )
                        .replace(
                                "/",
                                "_"
                        );

        if (!normalized.toLowerCase().endsWith(".xlsx")) {
            normalized += ".xlsx";
        }

        return normalized;
    }

    private String normalizeSheetName(
            String sheetName
    ) {
        String normalized =
                sheetName
                        .replaceAll(
                                "[\\\\/?*\\[\\]:]",
                                "_"
                        )
                        .trim();

        if (normalized.isBlank()) {
            normalized =
                    "Sheet1";
        }

        if (normalized.length() > 31) {
            normalized =
                    normalized.substring(
                            0,
                            31
                    );
        }

        return normalized;
    }

    private String getRequiredString(
            Map<String, Object> arguments,
            String key
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        key
                );

        if (value == null) {
            return null;
        }

        String text =
                value.toString();

        return text.isBlank()
                ? null
                : text;
    }

    private List<String> getStringList(
            Map<String, Object> arguments,
            String key
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        key
                );

        if (!(value instanceof List<?> values)) {
            return null;
        }

        List<String> result =
                new ArrayList<>();

        for (Object item : values) {
            result.add(
                    item == null
                            ? ""
                            : item.toString()
            );
        }

        return result;
    }

    private List<List<Object>> getRows(
            Map<String, Object> arguments,
            String key
    ) {
        if (arguments == null) {
            return null;
        }

        Object value =
                arguments.get(
                        key
                );

        if (!(value instanceof List<?> values)) {
            return null;
        }

        List<List<Object>> result =
                new ArrayList<>();

        for (Object rowValue : values) {
            if (!(rowValue instanceof List<?> row)) {
                return null;
            }

            result.add(
                    new ArrayList<>(
                            row
                    )
            );
        }

        return result;
    }
}