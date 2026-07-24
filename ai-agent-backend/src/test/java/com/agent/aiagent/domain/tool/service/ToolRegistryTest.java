package com.agent.aiagent.domain.tool.service;

import com.agent.aiagent.domain.tool.model.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    void 등록된_Tool_명세를_조회한다() {
        ToolRegistry toolRegistry =
                new ToolRegistry(
                        List.of(
                                new CurrentTimeTool()
                        )
                );

        List<ToolSpecification> specifications =
                toolRegistry.getSpecifications();

        assertThat(specifications)
                .hasSize(1);

        ToolSpecification specification =
                specifications.getFirst();

        assertThat(specification.name())
                .isEqualTo(
                        "current_time"
                );

        assertThat(specification.description())
                .isNotBlank();

        assertThat(specification.parameters())
                .containsKey(
                        "zoneId"
                );

        assertThat(
                specification
                        .parameters()
                        .get(
                                "zoneId"
                        )
                        .type()
        ).isEqualTo(
                "string"
        );

        assertThat(
                specification
                        .parameters()
                        .get(
                                "zoneId"
                        )
                        .required()
        ).isFalse();
    }
}