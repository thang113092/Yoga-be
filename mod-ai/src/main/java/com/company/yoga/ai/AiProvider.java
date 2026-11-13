package com.company.yoga.ai;

import java.util.Map;

/**
 * Text generation behind an interface, like {@code PaymentGateway} and {@code VideoHostingProvider}: business code
 * never sees provider types, so another provider can be added without touching it.
 */
public interface AiProvider {

    /** Short name stored with every result, e.g. {@code gemini}. */
    String name();

    boolean isConfigured();

    /** One request whose answer must be a JSON object matching {@code responseSchema}. */
    Result generateJson(JsonRequest request);

    /**
     * @param responseSchema schema of the answer, in the provider-neutral subset: type (object/array/string/integer/
     *                       number/boolean), properties, required, items, enum, nullable, description
     * @param thinkingLevel  optional, sent only when set (models that take a level)
     * @param thinkingBudget optional, sent only when set (models that take a token budget)
     */
    record JsonRequest(String model, String systemInstruction, String userText, Map<String, Object> responseSchema,
                       int maxOutputTokens, String thinkingLevel, Integer thinkingBudget) {
    }

    record Result(String text, String finishReason, Usage usage) {
    }

    /** Tokens as billed: {@code inputTokens} includes {@code cachedTokens}; {@code outputTokens} includes thinking. */
    record Usage(long inputTokens, long cachedTokens, long outputTokens) {
        public static final Usage NONE = new Usage(0, 0, 0);
    }

    enum Failure {
        /** The provider's safety filters refused the prompt or the answer. */
        BLOCKED,
        /** The answer hit the output token limit. */
        TRUNCATED,
        RATE_LIMITED,
        UNAVAILABLE,
        /** Our request was rejected (wrong key, unknown model...): a configuration problem. */
        REJECTED,
        BAD_RESPONSE
    }

    class ProviderException extends RuntimeException {
        private final Failure failure;
        private final Usage usage;

        public ProviderException(Failure failure, String message, Usage usage) {
            super(message);
            this.failure = failure;
            this.usage = usage == null ? Usage.NONE : usage;
        }

        public Failure failure() {
            return failure;
        }

        /** Tokens already billed before the failure (blocked or truncated answers are still charged). */
        public Usage usage() {
            return usage;
        }

        public boolean retryable() {
            return failure == Failure.RATE_LIMITED || failure == Failure.UNAVAILABLE;
        }
    }
}
