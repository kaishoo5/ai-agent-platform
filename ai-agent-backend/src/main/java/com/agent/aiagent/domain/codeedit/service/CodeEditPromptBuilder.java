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
                  "fieldName": null,
                  "methodName": null,
                  "code": "java.time.LocalDateTime",
                  "path": null
                }

                2. add_field

                {
                  "type": "add_field",
                  "className": "CalculatorTool",
                  "fieldName": null,
                  "methodName": null,
                  "code": "private LocalDateTime createdAt = LocalDateTime.now();",
                  "path": null
                }

                3. append_method

                {
                  "type": "append_method",
                  "className": "CalculatorTool",
                  "fieldName": null,
                  "methodName": null,
                  "code": "public LocalDateTime getCreatedAt() { return createdAt; }",
                  "path": null
                }

                4. replace_method

                {
                  "type": "replace_method",
                  "className": "CalculatorTool",
                  "fieldName": null,
                  "methodName": "execute",
                  "code": "수정된 전체 메서드 코드",
                  "path": null
                }

                5. remove_field

                {
                  "type": "remove_field",
                  "className": "CalculatorTool",
                  "fieldName": "createdAt",
                  "methodName": null,
                  "code": null,
                  "path": null
                }

                6. remove_method

                {
                  "type": "remove_method",
                  "className": "CalculatorTool",
                  "fieldName": null,
                  "methodName": "getCreatedAt",
                  "code": null,
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
                9. 필드를 삭제해야 할 때는 remove_field를 사용하세요.
                10. 메서드를 삭제해야 할 때는 remove_method를 사용하세요.
                11. remove_field에서는 fieldName을 반드시 지정하세요.
                12. remove_method에서는 methodName을 반드시 지정하세요.
                13. remove_field와 remove_method에서는 code를 null로 반환하세요.
                14. replace_method의 code에는 반드시 전체 메서드 코드를 넣어야 합니다.
                15. append_method의 code에는 반드시 전체 메서드 코드를 넣어야 합니다.
                16. 요청하지 않은 수정은 생성하지 마세요.
                17. ```java 또는 ```json 같은 markdown fence를 절대 사용하지 마세요.
                18. 사용자가 path를 지정하지 않았다면 path는 null로 반환하세요.

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