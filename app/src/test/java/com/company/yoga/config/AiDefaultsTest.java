package com.company.yoga.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.yoga.ai.AiConfig;
import com.company.yoga.ai.AiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** The real application.yml: on Render only YOGA_AI_ENABLED and GEMINI_API_KEY are needed to turn every AI feature on. */
class AiDefaultsTest {

    private static MockEnvironment env(String... keyValues) throws Exception {
        var env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        for (int i = 0; i < keyValues.length; i += 2) env.setProperty(keyValues[i], keyValues[i + 1]);
        return env;
    }

    private static ApplicationContextRunner runner(MockEnvironment env) {
        return new ApplicationContextRunner().withConfiguration(UserConfigurations.of(AiConfig.class))
                .withInitializer(ctx -> ctx.setEnvironment(env));
    }

    @Test
    void keyAloneTurnsEveryFeatureOnWithTheDefaultModel() throws Exception {
        runner(env("YOGA_AI_ENABLED", "true", "GEMINI_API_KEY", "k")).run(context -> {
            assertThat(context).hasNotFailed();
            AiProperties p = context.getBean(AiProperties.class);
            for (String key : List.of(AiProperties.VIDEO_TAGGING, AiProperties.MANAGER_CHAT, AiProperties.STUDENT_CHAT, AiProperties.STAFF_CHAT)) {
                assertThat(p.isOn(key)).as(key).isTrue();
                assertThat(p.feature(key).model()).isEqualTo("gemini-3.5-flash-lite");
                assertThat(p.feature(key).priceInput()).isEqualByComparingTo("0.30");
                assertThat(p.feature(key).priceOutput()).isEqualByComparingTo("2.50");
            }
        });
    }

    @Test
    void blankModelTurnsOneFeatureOff() throws Exception {
        runner(env("YOGA_AI_ENABLED", "true", "GEMINI_API_KEY", "k", "YOGA_AI_STUDENT_CHAT_MODEL", "")).run(context -> {
            AiProperties p = context.getBean(AiProperties.class);
            assertThat(p.isOn(AiProperties.STUDENT_CHAT)).isFalse();
            assertThat(p.isOn(AiProperties.MANAGER_CHAT)).isTrue();
        });
    }

    @Test
    void nothingSetMeansAiOff() throws Exception {
        runner(env()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiProperties.class).isOn(AiProperties.MANAGER_CHAT)).isFalse();
        });
    }
}
