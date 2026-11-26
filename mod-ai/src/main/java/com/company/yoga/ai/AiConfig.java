package com.company.yoga.ai;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
@RequiredArgsConstructor
@Slf4j
public class AiConfig {

    private final AiProperties properties;

    /** Runs streamed assistant answers (SSE) off the request thread; bounded so a burst cannot exhaust the server. */
    @org.springframework.context.annotation.Bean(name = "aiStreamExecutor")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor aiStreamExecutor() {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-stream-");
        executor.initialize();
        return executor;
    }

    @PostConstruct
    void check() {
        properties.validate();
        if (!properties.enabled()) {
            log.info("AI features are off (yoga.ai.enabled=false)");
            return;
        }
        properties.features().forEach((key, feature) -> log.info("AI feature {}: {}", key,
                feature.hasModel() ? feature.model() : "off (no model)"));
    }
}
