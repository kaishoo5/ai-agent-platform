package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.CodeEditRequest;
import org.springframework.stereotype.Component;

@Component
public class CodeEditPromptBuilder {

    public String build(
            CodeEditRequest request
    ) {
        return """
                당신은 Java 코드 수정 Agent입니다.

                사용자의 요청을 분석해서 필요한 코드 수정 작업을
                JSON 배열 형태의 Patch 목록으로 반환하세요.

                반드시 JSON 배열만 응답하세요.
                설명, 마크다운, 코드블록은 절대 포함하지 마세요.

                지원하는 Patch type:

                1. add_import

                {
                  "type": "add_import",
                  "className": "CalculatorTool",
                  "methodName": null,
                  "code": "java.time.LocalDateTime",
                  "path": null
                }

                2. add_field

                {
                  "type": "add_field",
                  "className": "CalculatorTool",
                  "methodName": null,
                  "code": "private LocalDateTime createdAt = LocalDateTime.now();",
                  "path": null
                }

                3. append_method

                {
                  "type": "append_method",
                  "className": "CalculatorTool",
                  "methodName": null,
                  "code": "public LocalDateTime getCreatedAt() { return createdAt; }",
                  "path": null
                }

                4. replace_method

                {
                  "type": "replace_method",
                  "className": "CalculatorTool",
                  "methodName": "execute",
                  "code": "수정된 전체 메서드 코드",
                  "path": null
                }

                규칙:

                1. 반드시 JSON 배열만 반환하세요.
                2. 지원하는 type만 사용하세요.
                3. 작업이 여러 개 필요하면 여러 Patch를 생성하세요.
                4. Patch 실행 순서를 고려하세요.
                5. import가 필요하면 add_import를 먼저 생성하세요.
                6. 필드가 필요하면 add_field를 생성하세요.
                7. 새로운 메서드는 append_method를 사용하세요.
                8. 기존 메서드를 수정해야 할 때만 replace_method를 사용하세요.
                9. replace_method의 code에는 반드시 전체 메서드 코드를 넣어야 합니다.
                10. append_method의 code에는 반드시 전체 메서드 코드를 넣어야 합니다.
                11. 요청하지 않은 수정은 생성하지 마세요.
                12. ```java 또는 ```json 같은 markdown fence를 절대 사용하지 마세요.
                13. 사용자가 path를 지정하지 않았다면 path는 null로 반환하세요.

                사용자 수정 요청:
                %s

                요청 path:
                %s
                """.formatted(
                request.instruction(),
                request.path()
        );
    }
}