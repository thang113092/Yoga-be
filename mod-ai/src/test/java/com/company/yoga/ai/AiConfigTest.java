package com.company.yoga.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Properties validation, cost arithmetic and prompt files. */
class AiConfigTest {

    private static final AiProperties.Gemini KEY = new AiProperties.Gemini("k", "https://g.test", 60, 2);
    private static final AiProperties.Quota QUOTA = new AiProperties.Quota(30, 100, new BigDecimal("20"), 80);

    private static AiProperties.Feature feature(String model, String in, String cached, String out) {
        return new AiProperties.Feature(model, 1024, null, null, in == null ? null : new BigDecimal(in),
                cached == null ? null : new BigDecimal(cached), out == null ? null : new BigDecimal(out));
    }

    private static AiProperties props(boolean enabled, AiProperties.Gemini gemini, AiProperties.Feature tagging) {
        return new AiProperties(enabled, "gemini", gemini, tagging == null ? Map.of() : Map.of(AiProperties.VIDEO_TAGGING, tagging), QUOTA);
    }

    @Test
    void disabledNeedsNothing() {
        props(false, new AiProperties.Gemini("", "u", 60, 2), feature("m", null, null, null)).validate();
    }

    @Test
    void enabledNeedsAKey() {
        assertThatThrownBy(() -> props(true, new AiProperties.Gemini("", "u", 60, 2), null).validate())
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void aModelNeedsItsPrices() {
        assertThatThrownBy(() -> props(true, KEY, feature("m", "0.3", null, null)).validate()).hasMessageContaining("price");
        props(true, KEY, feature("m", "0.3", null, "2.5")).validate();
    }

    @Test
    void featureWithoutModelIsOffButValid() {
        AiProperties p = props(true, KEY, feature("", null, null, null));
        p.validate();
        assertThat(p.isOn(AiProperties.VIDEO_TAGGING)).isFalse();
        assertThat(props(true, KEY, feature("m", "1", null, "1")).isOn(AiProperties.VIDEO_TAGGING)).isTrue();
        assertThat(props(false, KEY, feature("m", "1", null, "1")).isOn(AiProperties.VIDEO_TAGGING)).isFalse();
    }

    @Test
    void unknownFeatureIsRejected() {
        var p = new AiProperties(true, "gemini", KEY, Map.of("chat", feature("m", "1", null, "1")), QUOTA);
        assertThatThrownBy(p::validate).hasMessageContaining("unknown feature");
    }

    @Test
    void costChargesCachedInputAtTheCachedPrice() {
        // 1M uncached input at $0.30 + 1M cached at $0.03 + 1M output at $2.50 = $2.83
        var usage = new AiProvider.Usage(2_000_000, 1_000_000, 1_000_000);
        assertThat(AiUsageService.cost(feature("m", "0.30", "0.03", "2.50"), usage)).isEqualTo(2_830_000);
        // No cached price configured: cached tokens cost as normal input.
        assertThat(AiUsageService.cost(feature("m", "0.30", null, "2.50"), usage)).isEqualTo(3_100_000);
        // A small call rounds up to at least one micro dollar.
        assertThat(AiUsageService.cost(feature("m", "0.30", null, "2.50"), new AiProvider.Usage(1, 0, 0))).isEqualTo(1);
    }

    @Test
    void promptFileHasVersionAndText() {
        Prompts.Prompt prompt = Prompts.load(VideoTaggingService.PROMPT);
        assertThat(prompt.version()).isEqualTo("video-tagging/1");
        assertThat(prompt.text()).startsWith("Bạn là biên tập viên").doesNotContain("version:");
    }

    @Test
    void promptWithoutHeaderIsRejected() {
        assertThatThrownBy(() -> Prompts.parse("x.md", "Just text")).hasMessageContaining("version");
    }
}
