package com.company.yoga.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Binding as application.yml does it: unset environment variables arrive as empty strings. */
class AiPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(AiConfig.class))
            .withPropertyValues(
                    "yoga.ai.enabled=false", "yoga.ai.provider=gemini", "yoga.ai.gemini.api-key=",
                    "yoga.ai.features.video-tagging.model=", "yoga.ai.features.video-tagging.max-output-tokens=2048",
                    "yoga.ai.features.video-tagging.thinking-level=", "yoga.ai.features.video-tagging.thinking-budget=",
                    "yoga.ai.features.video-tagging.price-input=", "yoga.ai.features.video-tagging.price-cached-input=",
                    "yoga.ai.features.video-tagging.price-output=", "yoga.ai.quota.daily-budget-usd=20");

    @Test
    void defaultsStartWithAiOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AiProperties p = context.getBean(AiProperties.class);
            assertThat(p.isOn(AiProperties.VIDEO_TAGGING)).isFalse();
            AiProperties.Feature f = p.feature(AiProperties.VIDEO_TAGGING);
            assertThat(f.thinkingBudget()).isNull();
            assertThat(f.priceInput()).isNull();
            assertThat(f.maxOutputTokens()).isEqualTo(2048);
            assertThat(p.gemini().maxRetries()).isEqualTo(2);
            assertThat(p.quota().staffRequestsPerDay()).isEqualTo(100);
        });
    }

    @Test
    void configuredFeatureStarts() {
        runner.withPropertyValues("yoga.ai.enabled=true", "yoga.ai.gemini.api-key=k",
                        "yoga.ai.features.video-tagging.model=some-model", "yoga.ai.features.video-tagging.thinking-budget=0",
                        "yoga.ai.features.video-tagging.price-input=0.30", "yoga.ai.features.video-tagging.price-output=2.50")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiProperties p = context.getBean(AiProperties.class);
                    assertThat(p.isOn(AiProperties.VIDEO_TAGGING)).isTrue();
                    assertThat(p.feature(AiProperties.VIDEO_TAGGING).priceInput()).isEqualByComparingTo(new BigDecimal("0.30"));
                    assertThat(p.feature(AiProperties.VIDEO_TAGGING).thinkingBudget()).isZero();
                });
    }

    @Test
    void enabledWithoutKeyDoesNotStart() {
        runner.withPropertyValues("yoga.ai.enabled=true").run(context -> assertThat(context).hasFailed()
                .getFailure().hasStackTraceContaining("GEMINI_API_KEY"));
    }

    @Test
    void modelWithoutPriceDoesNotStart() {
        runner.withPropertyValues("yoga.ai.enabled=true", "yoga.ai.gemini.api-key=k", "yoga.ai.features.video-tagging.model=m")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("price"));
    }
}
