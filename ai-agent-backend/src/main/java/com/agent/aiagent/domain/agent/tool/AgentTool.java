package com.agent.aiagent.domain.agent.tool;

public interface AgentTool {

    /**
     * LLM과 ToolManager가 식별할 Tool 이름이다.
     *
     * 예:
     * current_time
     * calculator
     * weather
     */
    String getName();

    /**
     * Tool이 어떤 기능을 수행하는지 설명한다.
     *
     * 이후 Ollama가 어떤 Tool을 사용할지 판단할 때
     * 이 설명을 프롬프트에 포함한다.
     */
    String getDescription();

    /**
     * 현재 Tool이 처리할 수 있는 요청인지 판단한다.
     *
     * 초기 단계에서는 키워드 기반으로 사용하고,
     * 이후에는 Ollama가 Tool을 선택하는 방식으로 변경한다.
     */
    boolean supports(
            String question
    );

    /**
     * Tool을 실제로 실행한다.
     *
     * @param question 사용자의 원본 질문
     * @return Tool 실행 결과
     */
    String execute(
            String question
    );
}