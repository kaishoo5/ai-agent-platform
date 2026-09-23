package com.agent.aiagent.domain.memory.repository;

import com.agent.aiagent.domain.memory.entity.AgentMemory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, String> {
}
