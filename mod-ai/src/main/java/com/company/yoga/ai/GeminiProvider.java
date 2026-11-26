package com.company.yoga.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Google Gemini API ({@code models/{model}:generateContent}) over {@link RestClient}, like the PayOS, Zoom and Bunny
 * clients. The API key travels in the {@code x-goog-api-key} header, never in the URL. Prompts and answers are never
 * logged: only model, tokens and finish reason.
 */
@Component
@Slf4j
public class GeminiProvider implements AiProvider {

    /** Finish reasons meaning the safety or policy filters stopped the answer. */
    private static final Set<String> BLOCKING = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
            "IMAGE_SAFETY", "LANGUAGE");
    /** The model produced a tool call we cannot use. */
    private static final Set<String> BAD_CALL = Set.of("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient api;
    private final String apiKey;
    private final int maxRetries;
    private final Sleeper sleeper;

    @Autowired
    public GeminiProvider(AiProperties properties) {
        this(client(properties.gemini()), properties.gemini().apiKey(), properties.gemini().maxRetries(),
                d -> Thread.sleep(d.toMillis()));
    }

    GeminiProvider(RestClient api, String apiKey, int maxRetries, Sleeper sleeper) {
        this.api = api;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
    }

    private static RestClient client(AiProperties.Gemini gemini) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(gemini.timeoutSeconds()));
        return RestClient.builder().baseUrl(gemini.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    // ---------------------------------------------------------------- JSON answers

    @Override
    public Result generateJson(JsonRequest request) {
        JsonNode response = send(request.model(), body(request));
        JsonNode candidate = candidate(request.model(), response);
        Usage usage = usage(response);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            text.append(part.path("text").asText(""));
        }
        String finish = candidate.path("finishReason").asText("");
        if (text.isEmpty()) throw new ProviderException(Failure.BAD_RESPONSE, "Answer without text (finish " + finish + ")", usage);
        return new Result(text.toString(), finish, usage);
    }

    static Map<String, Object> body(JsonRequest request) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("responseMimeType", "application/json");
        generation.put("responseSchema", geminiSchema(request.responseSchema()));
        generation.put("maxOutputTokens", request.maxOutputTokens());
        thinking(generation, request.thinkingLevel(), request.thinkingBudget());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.userText())))));
        body.put("generationConfig", generation);
        return body;
    }

    // ---------------------------------------------------------------- conversations with tools

    @Override
    public ChatResult chat(ChatRequest request) {
        JsonNode response = send(request.model(), body(request));
        JsonNode candidate = candidate(request.model(), response);
        Usage usage = usage(response);
        String finish = candidate.path("finishReason").asText("");
        JsonNode parts = candidate.path("content").path("parts");

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode call = part.get("functionCall");
                Map<String, Object> args = call.hasNonNull("args") ? JSON.convertValue(call.get("args"), MAP) : Map.of();
                calls.add(new ToolCall(call.path("id").asText(null), call.path("name").asText(""), args));
            } else if (!part.path("thought").asBoolean(false)) {
                text.append(part.path("text").asText(""));
            }
        }
        if (calls.isEmpty() && text.isEmpty()) {
            throw new ProviderException(Failure.BAD_RESPONSE, "Turn without text or call (finish " + finish + ")", usage);
        }
        return new ChatResult(new Turn(Role.MODEL, text.toString(), calls, List.of(), parts.deepCopy()), finish, usage);
    }

    static Map<String, Object> body(ChatRequest request) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Turn turn : request.history()) contents.add(content(turn));

        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("maxOutputTokens", request.maxOutputTokens());
        thinking(generation, request.thinkingLevel(), request.thinkingBudget());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        body.put("contents", contents);
        if (!request.tools().isEmpty()) {
            List<Map<String, Object>> declarations = new ArrayList<>();
            for (ToolSpec tool : request.tools()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", tool.name());
                d.put("description", tool.description());
                if (tool.parameters() != null && !tool.parameters().isEmpty()) d.put("parameters", geminiSchema(tool.parameters()));
                declarations.add(d);
            }
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
            body.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", request.allowTools() ? "AUTO" : "NONE")));
        }
        body.put("generationConfig", generation);
        return body;
    }

    static Map<String, Object> content(Turn turn) {
        return switch (turn.role()) {
            case USER -> Map.of("role", "user", "parts", List.of(Map.of("text", turn.text() == null ? "" : turn.text())));
            case MODEL -> {
                if (turn.raw() != null) yield Map.of("role", "model", "parts", turn.raw());
                List<Map<String, Object>> parts = new ArrayList<>();
                if (turn.text() != null && !turn.text().isEmpty()) parts.add(Map.of("text", turn.text()));
                for (ToolCall call : turn.calls()) parts.add(Map.of("functionCall", functionCall(call)));
                yield Map.of("role", "model", "parts", parts);
            }
            case TOOL -> {
                List<Map<String, Object>> parts = new ArrayList<>();
                for (ToolResult result : turn.results()) {
                    Map<String, Object> response = new LinkedHashMap<>();
                    if (result.id() != null) response.put("id", result.id());
                    response.put("name", result.name());
                    response.put("response", result.response());
                    parts.add(Map.of("functionResponse", response));
                }
                yield Map.of("role", "user", "parts", parts);
            }
        };
    }

    private static Map<String, Object> functionCall(ToolCall call) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (call.id() != null) out.put("id", call.id());
        out.put("name", call.name());
        out.put("args", call.args());
        return out;
    }

    // ---------------------------------------------------------------- shared

    private static void thinking(Map<String, Object> generation, String level, Integer budget) {
        Map<String, Object> thinking = new LinkedHashMap<>();
        if (level != null && !level.isBlank()) thinking.put("thinkingLevel", level.trim());
        if (budget != null) thinking.put("thinkingBudget", budget);
        if (!thinking.isEmpty()) generation.put("thinkingConfig", thinking);
    }

    /** Gemini's schema subset spells types in upper case (OBJECT, STRING...). */
    static Object geminiSchema(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put((String) k, "type".equals(k) && v instanceof String s
                    ? s.toUpperCase(Locale.ROOT) : geminiSchema(v)));
            return out;
        }
        if (node instanceof List<?> list) return list.stream().map(GeminiProvider::geminiSchema).toList();
        return node;
    }

    /** POST with retries on 429/5xx. */
    private JsonNode send(String model, Map<String, Object> body) {
        if (!isConfigured()) throw new ProviderException(Failure.REJECTED, "Gemini API key is not configured", null);
        for (int attempt = 0; ; attempt++) {
            try {
                return post(model, body);
            } catch (ProviderException e) {
                if (!e.retryable() || attempt >= maxRetries) throw e;
                log.warn("Gemini {} ({}), retry {}/{}", model, e.failure(), attempt + 1, maxRetries);
                try {
                    sleeper.sleep(Duration.ofMillis(800L * (attempt + 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private JsonNode post(String model, Map<String, Object> body) {
        try {
            JsonNode response = api.post().uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) throw new ProviderException(Failure.BAD_RESPONSE, "Empty Gemini response", null);
            return response;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            Failure failure = status == 429 ? Failure.RATE_LIMITED : status >= 500 ? Failure.UNAVAILABLE : Failure.REJECTED;
            if (failure == Failure.REJECTED) {
                // 400/401/403/404: wrong key, unknown model or invalid schema. The error body does not echo the prompt.
                log.error("Gemini rejected the request for model {}: HTTP {} {}", model, status, abbreviate(e.getResponseBodyAsString()));
            }
            throw new ProviderException(failure, "Gemini HTTP " + status, null);
        } catch (ResourceAccessException e) {
            throw new ProviderException(Failure.UNAVAILABLE, "Gemini unreachable: " + e.getMessage(), null);
        }
    }

    static Usage usage(JsonNode response) {
        JsonNode meta = response.path("usageMetadata");
        return new Usage(meta.path("promptTokenCount").asLong(), meta.path("cachedContentTokenCount").asLong(),
                meta.path("candidatesTokenCount").asLong() + meta.path("thoughtsTokenCount").asLong());
    }

    /** The first candidate, after turning blocked, cut or broken answers into {@link ProviderException}. */
    static JsonNode candidate(String model, JsonNode response) {
        Usage usage = usage(response);
        JsonNode candidate = response.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            String blockReason = response.path("promptFeedback").path("blockReason").asText("");
            log.warn("Gemini {} returned no answer (block reason {})", model, blockReason.isEmpty() ? "-" : blockReason);
            throw new ProviderException(blockReason.isEmpty() ? Failure.BAD_RESPONSE : Failure.BLOCKED, "No candidate", usage);
        }
        String finish = candidate.path("finishReason").asText("");
        log.info("Gemini {}: finish={} in={} cached={} out={}", model, finish, usage.inputTokens(), usage.cachedTokens(), usage.outputTokens());
        if (BLOCKING.contains(finish)) throw new ProviderException(Failure.BLOCKED, "Finish reason " + finish, usage);
        if ("MAX_TOKENS".equals(finish)) throw new ProviderException(Failure.TRUNCATED, "Answer cut at max output tokens", usage);
        if (BAD_CALL.contains(finish)) throw new ProviderException(Failure.BAD_RESPONSE, "Finish reason " + finish, usage);
        return candidate;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
