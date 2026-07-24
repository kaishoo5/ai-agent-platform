package com.agent.aiagent.infra.provider.ollama;

import com.agent.aiagent.infra.provider.ollama.dto.OllamaTool;
import com.agent.aiagent.infra.provider.ollama.dto.OllamaToolFunction;
import com.agent.aiagent.infra.provider.ollama.dto.OllamaToolParameters;
import com.agent.aiagent.provider.chat.ChatModelTool;
import com.agent.aiagent.provider.chat.ChatModelToolParameter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OllamaToolMapper {

    public OllamaTool map(
            ChatModelTool chatModelTool
    ) {
        return OllamaTool.builder()
                .type("function")
                .function(
                        OllamaToolFunction.builder()
                                .name(chatModelTool.name())
                                .description(chatModelTool.description())
                                .parameters(
                                        createParameters(
                                                chatModelTool.parameters()
                                        )
                                )
                                .build()
                )
                .build();
    }

    private OllamaToolParameters createParameters(
            Map<String, ChatModelToolParameter> parameters
    ) {
        Map<String, Object> properties =
                new LinkedHashMap<>();

        parameters.forEach(
                (name, parameter) ->
                        properties.put(
                                name,
                                createProperty(
                                        parameter
                                )
                        )
        );

        List<String> required =
                parameters.entrySet()
                        .stream()
                        .filter(
                                entry ->
                                        entry.getValue()
                                                .required()
                        )
                        .map(Map.Entry::getKey)
                        .toList();

        return OllamaToolParameters.builder()
                .type("object")
                .properties(properties)
                .required(required)
                .build();
    }

    private Map<String, Object> createProperty(
            ChatModelToolParameter parameter
    ) {
        Map<String, Object> property =
                new LinkedHashMap<>();

        property.put(
                "type",
                parameter.type()
        );

        property.put(
                "description",
                parameter.description()
        );

        return property;
    }
}