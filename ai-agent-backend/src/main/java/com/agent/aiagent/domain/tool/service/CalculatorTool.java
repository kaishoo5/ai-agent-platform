package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolResult;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CalculatorTool implements AgentTool {

    private static final ToolSpecification SPECIFICATION =
            new ToolSpecification(
                    "calculator",
                    "수학식을 계산합니다. 덧셈, 뺄셈, 곱셈, 나눗셈, 괄호와 소수 계산을 지원합니다.",
                    Map.of(
                            "expression",
                            new ToolParameter(
                                    "string",
                                    "계산할 수학식입니다. 예: (127 * 53) + 10",
                                    true
                            )
                    )
            );

    @Override
    public ToolSpecification getSpecification() {
        return SPECIFICATION;
    }

    @Override
    public ToolResult execute(
            Map<String, Object> arguments
    ) {
        String expressionValue =
                getExpressionValue(
                        arguments
                );

        if (expressionValue == null) {
            return ToolResult.failure(
                    "계산할 수식이 없습니다."
            );
        }

        try {
            Expression expression =
                    new ExpressionBuilder(
                            expressionValue
                    ).build();

            double result =
                    expression.evaluate();

            if (!Double.isFinite(result)) {
                return ToolResult.failure(
                        "계산 결과가 유효하지 않습니다."
                );
            }

            return ToolResult.success(
                    formatResult(
                            result
                    )
            );
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(
                    "유효하지 않은 수식입니다: "
                            + expressionValue
            );
        } catch (ArithmeticException exception) {
            return ToolResult.failure(
                    "계산 중 오류가 발생했습니다: "
                            + exception.getMessage()
            );
        }
    }

    private String getExpressionValue(
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            return null;
        }

        Object expression =
                arguments.get(
                        "expression"
                );

        if (expression == null) {
            return null;
        }

        String value =
                expression.toString()
                        .trim();

        return value.isBlank()
                ? null
                : value;
    }

    private String formatResult(
            double result
    ) {
        if (result == Math.rint(result)) {
            return Long.toString(
                    (long) result
            );
        }

        return Double.toString(
                result
        );
    }
}