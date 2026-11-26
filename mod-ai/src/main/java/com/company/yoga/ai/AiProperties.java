package com.company.yoga.ai;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Block {@code yoga.ai} of application.yml. Everything that changes while operating (models, prices, quotas, token
 * limits) lives here, never in code. {@link #validate()} stops the application from starting on an unsafe setup.
 */
@ConfigurationProperties("yoga.ai")
public record AiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("gemini") String provider,
        @DefaultValue Gemini gemini,
        Map<String, Feature> features,
        @DefaultValue Quota quota,
        @DefaultValue Conversations conversations) {

    /** Feature keys used in code; the configuration may only add these. */
    public static final String VIDEO_TAGGING = "video-tagging";
    public static final String MANAGER_CHAT = "manager-chat";
    public static final String STUDENT_CHAT = "student-chat";
    private static final java.util.Set<String> KNOWN = java.util.Set.of(VIDEO_TAGGING, MANAGER_CHAT, STUDENT_CHAT);

    public record Gemini(
            @DefaultValue("") String apiKey,
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
            @DefaultValue("60") int timeoutSeconds,
            @DefaultValue("2") int maxRetries) {
    }

    /**
     * One AI feature. A blank model turns only this feature off. Prices are USD per one million tokens, as the
     * provider publishes them; {@code priceCachedInput} applies to the part of the prompt served from cache.
     */
    public record Feature(
            String model,
            @DefaultValue("1024") int maxOutputTokens,
            String thinkingLevel,
            Integer thinkingBudget,
            BigDecimal priceInput,
            BigDecimal priceCachedInput,
            BigDecimal priceOutput,
            /** Chat features: tool calls allowed for one question before a text answer is forced. */
            @DefaultValue("8") int maxToolRounds) {

        public boolean hasModel() {
            return model != null && !model.isBlank();
        }
    }

    public record Quota(
            @DefaultValue("30") int studentRequestsPerDay,
            @DefaultValue("100") int staffRequestsPerDay,
            /** Whole system per day (Vietnam time); 0 = no limit. */
            @DefaultValue("20") BigDecimal dailyBudgetUsd,
            @DefaultValue("80") int alertPercent) {
    }

    public record Conversations(
            /** Conversations untouched for this long are deleted. */
            @DefaultValue("30") int retentionDays,
            /** Stored turns per conversation (questions, answers and tool results); then a new one must be started. */
            @DefaultValue("60") int maxMessages) {
    }

    public AiProperties {
        features = features == null ? Map.of() : Map.copyOf(features);
    }

    public Feature feature(String key) {
        return features.get(key);
    }

    /** Feature usable right now: AI on, key present and a model chosen. */
    public boolean isOn(String key) {
        Feature feature = features.get(key);
        return enabled && !gemini.apiKey().isBlank() && feature != null && feature.hasModel();
    }

    /** Fails fast so a wrong deployment is seen at start-up, not on the first request. */
    public void validate() {
        if (!enabled) return;
        if (!"gemini".equals(provider)) throw new IllegalStateException("yoga.ai.provider: only 'gemini' is supported, got " + provider);
        if (gemini.apiKey().isBlank()) throw new IllegalStateException("yoga.ai.enabled=true but GEMINI_API_KEY is empty");
        if (gemini.maxRetries() < 0 || gemini.timeoutSeconds() <= 0) throw new IllegalStateException("yoga.ai.gemini: invalid timeout/retries");
        features.forEach((key, feature) -> {
            if (!KNOWN.contains(key)) throw new IllegalStateException("yoga.ai.features." + key + ": unknown feature");
            if (!feature.hasModel()) return;
            if (feature.priceInput() == null || feature.priceOutput() == null) {
                throw new IllegalStateException("yoga.ai.features." + key + ": model " + feature.model()
                        + " has no price-input/price-output; set them from the provider's price list");
            }
            if (feature.maxOutputTokens() <= 0) throw new IllegalStateException("yoga.ai.features." + key + ".max-output-tokens must be > 0");
            if (feature.maxToolRounds() < 1 || feature.maxToolRounds() > 20) throw new IllegalStateException("yoga.ai.features." + key + ".max-tool-rounds must be 1-20");
        });
        if (conversations.retentionDays() < 1 || conversations.maxMessages() < 4) throw new IllegalStateException("yoga.ai.conversations: invalid limits");
        if (quota.dailyBudgetUsd().signum() < 0) throw new IllegalStateException("yoga.ai.quota.daily-budget-usd must be >= 0");
    }
}
