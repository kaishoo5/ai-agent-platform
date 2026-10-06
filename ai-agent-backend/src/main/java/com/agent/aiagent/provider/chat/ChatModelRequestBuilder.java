package com.agent.aiagent.provider.chat;

import com.agent.aiagent.domain.tool.model.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatModelRequestBuilder {

    private static final String WEB_SEARCH_TOOL_NAME =
            "web_search";

    private static final String ATTACHMENT_PROJECT_STRUCTURE_TOOL_NAME =
            "attachment_project_structure";

    private static final String ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME =
            "attachment_codebase_overview";

    private static final String ATTACHMENT_SEARCH_TOOL_NAME =
            "attachment_search";

    private static final String ATTACHMENT_READ_SOURCE_TOOL_NAME =
            "attachment_read_source";

    private static final String WEB_SEARCH_GROUNDING_PROMPT =
            """
            방금 제공된 web_search 결과를 근거로 사용자의 질문에 답변하세요.

            검색 결과에 포함된 각 SOURCE는 서로 독립된 출처입니다.

            최종 답변을 작성할 때 다음 원칙을 지키세요.

            - SOURCE의 TITLE, URL, CONTENT에서 직접 확인되는 사실만 사용하세요.
            - SOURCE에 없는 숫자, 날짜, 금액, 제품명, 기능명, 국가, 인물, 사건의 원인이나 결과를 추가하지 마세요.
            - 서로 다른 SOURCE의 내용을 결합하여 새로운 사실이나 관계를 만들지 마세요.
            - 하나의 SOURCE 안에서도 서로 다른 인물이나 기관의 행동을 하나의 주체가 한 행동처럼 합치지 마세요.
            - 검색 결과가 어떤 사실을 충분히 뒷받침하지 못하면 그 사실을 단정하지 마세요.
            - 검색 결과의 내용을 해석하거나 요약할 수는 있지만 원문보다 더 구체적인 사실을 새로 만들지 마세요.
            - 검색 결과와 모델의 기존 지식이 충돌하거나 모델의 기존 지식에만 존재하는 내용이 있다면 검색 결과를 우선하세요.
            - 사용자의 질문에 답하기에 현재 검색 결과가 충분하다면 추가 검색을 하지 말고 최종 답변을 작성하세요.
            """;

    private static final String ATTACHMENT_PROJECT_STRUCTURE_GROUNDING_PROMPT =
            """
            방금 제공된 attachment_project_structure 결과는
            현재 대화에 첨부된 ZIP 프로젝트에서 실제로 확인된 파일 및 디렉터리 구조입니다.

            다음 단계에서는 attachment_codebase_overview를 사용하여
            프로젝트의 주요 실제 구현 코드를 확인하세요.

            프로젝트 구조만 보고 주요 기능, 동작, API, 설정, 의존성 또는
            아키텍처 구현을 추측하지 마세요.

            attachment_codebase_overview 결과를 확인한 뒤,
            특정 클래스나 기능에 대한 추가 구현 근거가 필요한 경우에만
            attachment_search를 사용하세요.
            """;

    private static final String ATTACHMENT_CODEBASE_OVERVIEW_GROUNDING_PROMPT =
            """
            방금 제공된 attachment_codebase_overview 결과는
            현재 대화에 첨부된 ZIP 프로젝트에서 선별한 주요 실제 소스 코드입니다.

            최종 답변을 작성할 때 다음 원칙을 지키세요.

            - 프로젝트 구조는 attachment_project_structure 결과에서 확인된 내용만 사용하세요.
            - 주요 기능과 구현 설명은 attachment_codebase_overview 또는 attachment_search에서 실제로 확인된 코드만 근거로 사용하세요.
            - 파일명이나 클래스명만 보고 구현 내용을 추측하지 마세요.
            - 확인되지 않은 API URL, HTTP Method, 요청/응답 형식, 설정 키, 설정값, 라이브러리 버전 또는 런타임 동작을 만들어내지 마세요.
            - 모델의 기존 지식을 현재 첨부 프로젝트의 구현인 것처럼 섞지 마세요.
            - 특정 기능의 구현 근거가 더 필요하면 attachment_search를 사용하세요.
            - 현재 확보한 코드만으로 충분히 답할 수 있다면 추가 도구를 호출하지 말고 최종 답변을 작성하세요.
            """;

    private static final String ATTACHMENT_SEARCH_GROUNDING_PROMPT =
            """
            방금 제공된 attachment_search 결과는
            현재 대화에 첨부된 파일에서 검색한 실제 코드 내용입니다.
    
            원래 사용자가 요청한 질문을 기준으로
            지금까지 확보한 프로젝트 구조, 코드베이스 개요 및
            attachment_search 결과를 함께 사용하여 답변하세요.
    
            반드시 다음 규칙을 지키세요.
    
            - attachment_search는 관련 파일과 코드 위치를 찾기 위한 검색 도구입니다.
            - 검색 결과에서 관련 SOURCE_PATH를 확인했다면,
              해당 파일의 정확한 구현을 확인하기 위해 attachment_read_source를 사용하세요.
            - 특정 파일의 SOURCE_PATH를 이미 알고 있다면
              같은 파일명, 함수명 또는 변수명으로 attachment_search를 반복하지 마세요.
            - 특정 함수의 전체 구현, API 호출, 컴포넌트 동작,
              store action 또는 데이터 흐름을 확인해야 한다면
              attachment_search를 반복하기보다 attachment_read_source로 실제 파일을 읽으세요.
            - 다른 관련 파일의 위치를 아직 모르는 경우에만
              attachment_search를 추가로 사용하세요.
    
            - attachment_search 결과에 실제로 포함된 코드만 구현 근거로 사용하세요.
            - 검색 결과에서 확인되지 않은 함수 본문을 재구성하거나 만들어내지 마세요.
            - 여러 검색 결과를 조합하여 존재하지 않는 코드 블록이나 pseudo-code를 만들지 마세요.
            - 실제 코드에 존재하지 않는 함수 호출 순서, API 호출 순서 또는 데이터 흐름을 추측하지 마세요.
            - 파일명, 함수명, 변수명, API 이름만 보고 내부 구현을 추론하지 마세요.
            - 실제 코드가 확인되지 않은 내용을
              "예상", "가정", "추정", "아마", "일반적으로" 등의 표현으로 보완하지 마세요.
            - 설명을 위해 예제 코드나 재구성된 코드를 새로 작성하지 마세요.
            - 코드 블록을 제시해야 한다면 Tool Result에 실제로 존재하는 코드만 사용하세요.
            - Tool Result에서 직접 확인할 수 있는 사실과 코드 흐름만 설명하세요.
    
            사용자가 특정 함수의 실행 순서나 데이터 흐름을 요청한 경우
            검색 결과에서 관련 SOURCE_PATH를 찾고,
            필요한 파일은 attachment_read_source로 실제 내용을 확인한 뒤
            확인된 코드 사이의 관계만 연결하여 설명하세요.
    
            하나의 파일을 읽은 뒤 다른 파일의 구현도 확인해야 한다면,
            현재 코드에서 확인된 import, component, action, 함수명 또는 경로를 기준으로
            다음 파일을 찾으세요.
    
            필요한 다른 파일의 정확한 SOURCE_PATH를 이미 알고 있다면
            attachment_read_source를 직접 사용하세요.
    
            정확한 SOURCE_PATH를 모르는 경우에만
            attachment_search를 사용하여 위치를 찾으세요.
    
            추가 검색과 파일 확인을 해도 확인할 수 없는 내용은 만들어내지 말고
            "현재 확인된 코드에서는 확인되지 않는다"고 명시하세요.
    
            현재 확보한 실제 코드만으로 사용자의 질문에 충분히 답할 수 있다면
            추가 도구를 호출하지 말고 최종 답변을 작성하세요.
    
            사용자의 원래 질문은 이전 user message에 포함되어 있습니다.
            질문이 없다고 판단하지 말고 이전 user message의 요청에 답변하세요.
            """;

    private static final String ATTACHMENT_READ_SOURCE_GROUNDING_PROMPT =
            """
            방금 제공된 attachment_read_source 결과는
            현재 대화에 첨부된 ZIP 프로젝트에서 직접 읽은 실제 소스 파일 내용입니다.
    
            해당 파일의 구현을 설명할 때는
            attachment_read_source 결과를 가장 직접적인 근거로 사용하세요.
    
            - SOURCE CONTENT에 실제로 존재하는 코드만 설명하세요.
            - 함수 본문, API 호출, import, 상태 변경 및 데이터 흐름을 임의로 재구성하지 마세요.
            - 현재 파일에서 사용자의 질문에 충분히 답할 수 있다면 추가 도구를 호출하지 마세요.
            - 다른 파일의 구현까지 확인해야 하고 정확한 SOURCE_PATH를 이미 알고 있다면
              attachment_read_source로 해당 파일을 직접 읽으세요.
            - 다른 파일이 필요하지만 정확한 SOURCE_PATH를 모르는 경우에만
              attachment_search를 사용하여 위치를 찾으세요.
            - 이미 읽은 동일한 SOURCE_PATH를 다시 읽지 마세요.
            - 확인되지 않는 내용은 추측하지 말고 확인 가능한 범위까지만 설명하세요.
    
            사용자의 원래 질문은 이전 user message에 포함되어 있습니다.
            원래 질문을 기준으로 필요한 코드 흐름을 계속 분석하세요.
            """;

    public ChatModelRequest appendToolResults(
            ChatModelRequest request,
            List<ChatModelToolCall> toolCalls,
            List<ToolResult> toolResults,
            List<ChatModelTool> originalTools
    ) {
        List<ChatModelMessage> messages =
                new ArrayList<>(
                        request.messages()
                );

        /*
         * 모델이 실행을 요청했던 Tool Call을
         * assistant message로 conversation에 추가한다.
         */
        messages.add(
                new ChatModelMessage(
                        "assistant",
                        null,
                        null,
                        toolCalls,
                        null
                )
        );

        boolean webSearchExecuted =
                false;

        boolean attachmentProjectStructureExecuted =
                false;

        boolean attachmentCodebaseOverviewExecuted =
                false;

        boolean attachmentSearchExecuted =
                false;

        boolean attachmentReadSourceExecuted =
                false;

        /*
         * 각 Tool Call에 대응하는 Tool Result를
         * tool message로 conversation에 추가한다.
         */
        for (
                int index = 0;
                index < toolResults.size();
                index++
        ) {
            ToolResult result =
                    toolResults.get(
                            index
                    );

            ChatModelToolCall toolCall =
                    toolCalls.get(
                            index
                    );

            messages.add(
                    new ChatModelMessage(
                            "tool",
                            result.content(),
                            null,
                            List.of(),
                            toolCall.name()
                    )
            );

            if (
                    result.success()
                            && WEB_SEARCH_TOOL_NAME.equals(
                            toolCall.name()
                    )
            ) {
                webSearchExecuted =
                        true;
            }

            if (
                    result.success()
                            && ATTACHMENT_PROJECT_STRUCTURE_TOOL_NAME.equals(
                            toolCall.name()
                    )
            ) {
                attachmentProjectStructureExecuted =
                        true;
            }

            if (
                    result.success()
                            && ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME.equals(
                            toolCall.name()
                    )
            ) {
                attachmentCodebaseOverviewExecuted =
                        true;
            }

            if (
                    result.success()
                            && ATTACHMENT_SEARCH_TOOL_NAME.equals(
                            toolCall.name()
                    )
            ) {
                attachmentSearchExecuted =
                        true;
            }

            if (
                    result.success()
                            && ATTACHMENT_READ_SOURCE_TOOL_NAME.equals(
                            toolCall.name()
                    )
            ) {
                attachmentReadSourceExecuted =
                        true;
            }
        }

        /*
         * Web Search가 실행된 경우
         * 검색 결과 기반으로만 답변하도록 grounding을 추가한다.
         */
        if (webSearchExecuted) {
            messages.add(
                    new ChatModelMessage(
                            "system",
                            WEB_SEARCH_GROUNDING_PROMPT,
                            null
                    )
            );
        }

        /*
         * ZIP 프로젝트 구조 분석이 실행된 경우
         * 구조만 보고 구현을 추측하지 않고
         * 다음 단계에서 overview를 사용하도록 유도한다.
         */
        if (attachmentProjectStructureExecuted) {
            messages.add(
                    new ChatModelMessage(
                            "system",
                            ATTACHMENT_PROJECT_STRUCTURE_GROUNDING_PROMPT,
                            null
                    )
            );
        }

        /*
         * ZIP 프로젝트 주요 코드 분석이 실행된 경우
         * 실제 코드 기반으로 답변하도록 grounding을 추가한다.
         */
        if (attachmentCodebaseOverviewExecuted) {
            messages.add(
                    new ChatModelMessage(
                            "system",
                            ATTACHMENT_CODEBASE_OVERVIEW_GROUNDING_PROMPT,
                            null
                    )
            );
        }

        /*
         * 첨부파일 코드 검색이 실행된 경우
         * 원래 사용자 질문을 다시 기준으로 삼도록 명시한다.
         *
         * 긴 Tool Calling chain 이후 모델이
         * "질문이 전달되지 않았다"고 판단하는 것을 방지하면서,
         * 현재 검색 결과가 충분하면 최종 답변을 작성하고
         * 부족한 경우에만 추가 attachment_search를 사용하도록 한다.
         */
        if (attachmentSearchExecuted) {
            messages.add(
                    new ChatModelMessage(
                            "system",
                            ATTACHMENT_SEARCH_GROUNDING_PROMPT,
                            null
                    )
            );
        }

        if (attachmentReadSourceExecuted) {
            messages.add(
                    new ChatModelMessage(
                            "system",
                            ATTACHMENT_READ_SOURCE_GROUNDING_PROMPT,
                            null
                    )
            );
        }

        List<ChatModelTool> tools =
                resolveNextTools(
                        request.tools(),
                        originalTools,
                        attachmentProjectStructureExecuted,
                        attachmentCodebaseOverviewExecuted
                );

        return new ChatModelRequest(
                request.modelType(),
                messages,
                tools
        );
    }

    /**
     * ZIP 프로젝트 분석 과정에서 다음 round에 제공할 Tool을 결정한다.
     *
     * overview까지 실행된 경우에는 ZIP 프로젝트의 기본 분석 단계가
     * 완료된 것이므로 originalTools를 다시 제공한다.
     *
     * structure만 실행된 경우에는 다음 단계에서 overview가 반드시
     * 실행되도록 attachment_codebase_overview만 제공한다.
     *
     * 그 외에는 현재 Tool 목록을 그대로 유지한다.
     */
    private List<ChatModelTool> resolveNextTools(
            List<ChatModelTool> currentTools,
            List<ChatModelTool> originalTools,
            boolean attachmentProjectStructureExecuted,
            boolean attachmentCodebaseOverviewExecuted
    ) {
        /*
         * structure와 overview가 같은 appendToolResults 호출에서
         * 함께 처리된 경우에도 overview 완료 상태를 우선한다.
         */
        if (attachmentCodebaseOverviewExecuted) {
            return originalTools;
        }

        if (attachmentProjectStructureExecuted) {
            return originalTools.stream()
                    .filter(tool ->
                            ATTACHMENT_CODEBASE_OVERVIEW_TOOL_NAME.equals(
                                    tool.name()
                            )
                    )
                    .toList();
        }

        return currentTools;
    }
}