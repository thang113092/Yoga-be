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
