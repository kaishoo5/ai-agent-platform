package com.agent.aiagent.domain.codeedit.service;

import com.agent.aiagent.domain.codeedit.model.MethodEditRequest;
import org.springframework.stereotype.Component;

@Component
public class MethodEditPromptBuilder {

    public String build(
            MethodEditRequest request,
            String currentMethodCode
    ) {

        return """
                당신은 Java 코드 수정 Agent입니다.

                사용자가 요청한 내용에 맞게 아래 메서드를 수정하세요.

                반드시 아래 JSON 형식으로만 응답하세요.
                설명, 마크다운, 코드블록은 절대 포함하지 마세요.

                {
                  "type": "replace_method",
                  "className": "%s",
                  "methodName": "%s",
                  "code": "수정된 전체 메서드 코드"
                }

                규칙:
                1. type은 반드시 "replace_method"여야 합니다.
                2. className과 methodName은 변경하지 마세요.
                3. code에는 수정된 전체 메서드 코드를 넣으세요.
                4. 메서드 일부만 반환하지 말고 전체 메서드를 반환하세요.
                5. 기존 코드 스타일과 들여쓰기를 최대한 유지하세요.
                6. 요청하지 않은 로직은 변경하지 마세요.
                7. JSON 외의 문장은 절대 출력하지 마세요.
                8. ```java 또는 ```json 같은 markdown fence를 절대 사용하지 마세요.

                대상 클래스:
                %s

                대상 메서드:
                %s

                사용자 수정 요청:
                %s

                현재 메서드 코드:
                %s
                """.formatted(
                request.className(),
                request.methodName(),
                request.className(),
                request.methodName(),
                request.instruction(),
                currentMethodCode
        );
    }
}