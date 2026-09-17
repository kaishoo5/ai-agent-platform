package com.agent.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ChatAsyncConfig {

    @Bean(
            name = "chatExecutor",
            destroyMethod = "close"
    )
    public ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}