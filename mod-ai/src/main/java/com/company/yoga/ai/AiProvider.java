package com.company.yoga.ai;

import java.util.List;
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
     * One model turn of a conversation that may call tools. The caller runs the requested tools and calls again with
     * their results appended. Returns the model turn to store and replay unchanged.
     */
    ChatResult chat(ChatRequest request);

    /** A function the model may call; {@code parameters} uses the same schema subset as {@link JsonRequest}. */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    enum Role { USER, MODEL, TOOL }

    record ToolCall(String id, String name, Map<String, Object> args) {
    }

    record ToolResult(String id, String name, Map<String, Object> response) {
    }

    /**
     * One stored turn. USER has text; MODEL has text and/or calls plus {@code raw}, the provider's own encoding that
     * must be replayed verbatim (it carries signatures of the model's reasoning); TOOL has results.
     */
    record Turn(Role role, String text, List<ToolCall> calls, List<ToolResult> results, Object raw) {
        public static Turn user(String text) {
            return new Turn(Role.USER, text, List.of(), List.of(), null);
        }

        public static Turn tool(List<ToolResult> results) {
            return new Turn(Role.TOOL, null, List.of(), results, null);
        }

        public Turn {
            calls = calls == null ? List.of() : List.copyOf(calls);
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    /** @param allowTools false forces a text answer (used for the last round) */
    record ChatRequest(String model, String systemInstruction, List<Turn> history, List<ToolSpec> tools, boolean allowTools,
                       int maxOutputTokens, String thinkingLevel, Integer thinkingBudget) {
    }

    record ChatResult(Turn turn, String finishReason, Usage usage) {
    }

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
