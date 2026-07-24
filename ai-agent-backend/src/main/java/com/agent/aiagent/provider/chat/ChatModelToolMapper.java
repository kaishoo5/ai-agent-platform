package com.agent.aiagent.provider.chat;

import com.agent.aiagent.domain.tool.model.ToolParameter;
import com.agent.aiagent.domain.tool.model.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ChatModelToolMapper {

    public ChatModelTool map(
            ToolSpecification specification
    ) {
        return new ChatModelTool(
                specification.name(),
                specification.description(),
                mapParameters(
                        specification.parameters()
                )
        );
    }

    private Map<String, ChatModelToolParameter> mapParameters(
            Map<String, ToolParameter> parameters
    ) {
        return parameters.entrySet()
                .stream()
                .collect(
                        Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> mapParameter(
                                        entry.getValue()
                                )
                        )
                );
    }

    private ChatModelToolParameter mapParameter(
            ToolParameter parameter
    ) {
        return new ChatModelToolParameter(
                parameter.type(),
                parameter.description(),
                parameter.required()
        );
    }
}