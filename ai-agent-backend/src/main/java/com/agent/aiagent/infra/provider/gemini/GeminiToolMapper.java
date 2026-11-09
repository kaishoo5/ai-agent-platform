package com.agent.aiagent.infra.provider.gemini;

import com.agent.aiagent.provider.chat.ChatModelTool;
import com.agent.aiagent.provider.chat.ChatModelToolParameter;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GeminiToolMapper {

    public List<Map<String, Object>> map(
            List<ChatModelTool> tools
    ) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> declarations =
                new ArrayList<>();

        for (ChatModelTool tool : tools) {
            declarations.add(createDeclaration(tool));
        }

        return List.of(
                Map.of("functionDeclarations", declarations)
        );
    }

    private Map<String, Object> createDeclaration(
            ChatModelTool tool
    ) {
        Map<String, Object> declaration =
                new LinkedHashMap<>();

        declaration.put("name", tool.name());

        declaration.put(
                "description",
                tool.description() == null
                        ? ""
                        : tool.description()
        );

        declaration.put(
                "parameters",
                createParameters(tool.parameters())
        );

        return declaration;
    }

    private Map<String, Object> createParameters(
            Map<String, ChatModelToolParameter> parameters
    ) {
        Map<String, Object> schema =
                new LinkedHashMap<>();

        schema.put("type", "OBJECT");

        Map<String, Object> properties =
                new LinkedHashMap<>();

        List<String> required =
                new ArrayList<>();

        if (parameters != null) {
            parameters.forEach((name, parameter) -> {
                properties.put(
                        name,
                        createProperty(parameter)
                );

                if (parameter.required()) {
                    required.add(name);
                }
            });
        }

        schema.put("properties", properties);

        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    private Map<String, Object> createProperty(
            ChatModelToolParameter parameter
    ) {
        Map<String, Object> property =
                new LinkedHashMap<>();

        String type = normalizeType(parameter.type());

        property.put("type", type);

        if (parameter.description() != null
                && !parameter.description().isBlank()) {
            property.put(
                    "description",
                    parameter.description()
            );
        }

        // Gemini는 ARRAY 타입에 items 스키마가 필수
        if ("ARRAY".equals(type)) {
            property.put(
                    "items",
                    createArrayItems(parameter)
            );
        }

        return property;
    }

    private Map<String, Object> createArrayItems(
            ChatModelToolParameter parameter
    ) {
        // 현재 ChatModelToolParameter에는 배열 원소 타입 정보가 없음.
        // 우선 STRING 원소로 선언하고, 이후 필요하면
        // 공통 ToolParameter 구조에 items 스키마를 추가한다.
        return Map.of(
                "type", "STRING"
        );
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "STRING";
        }

        String normalized =
                type.trim().toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "STRING" -> "STRING";
            case "INTEGER", "INT", "LONG" -> "INTEGER";
            case "NUMBER", "FLOAT", "DOUBLE" -> "NUMBER";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "ARRAY" -> "ARRAY";
            case "OBJECT" -> "OBJECT";
            default -> "STRING";
        };
    }
}
