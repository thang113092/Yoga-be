package com.company.yoga.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
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

    @Override
    public Result generateJson(JsonRequest request) {
        if (!isConfigured()) throw new ProviderException(Failure.REJECTED, "Gemini API key is not configured", null);
        Map<String, Object> body = body(request);
        for (int attempt = 0; ; attempt++) {
            try {
                return call(request.model(), body);
            } catch (ProviderException e) {
                if (!e.retryable() || attempt >= maxRetries) throw e;
                log.warn("Gemini {} ({}), retry {}/{}", request.model(), e.failure(), attempt + 1, maxRetries);
                try {
                    sleeper.sleep(Duration.ofMillis(800L * (attempt + 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    static Map<String, Object> body(JsonRequest request) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("responseMimeType", "application/json");
        generation.put("responseSchema", geminiSchema(request.responseSchema()));
        generation.put("maxOutputTokens", request.maxOutputTokens());
        Map<String, Object> thinking = new LinkedHashMap<>();
        if (request.thinkingLevel() != null && !request.thinkingLevel().isBlank()) thinking.put("thinkingLevel", request.thinkingLevel().trim());
        if (request.thinkingBudget() != null) thinking.put("thinkingBudget", request.thinkingBudget());
        if (!thinking.isEmpty()) generation.put("thinkingConfig", thinking);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.userText())))));
        body.put("generationConfig", generation);
        return body;
    }

    /** Gemini's schema subset spells types in upper case (OBJECT, STRING...). */
    @SuppressWarnings("unchecked")
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

    private Result call(String model, Map<String, Object> body) {
        JsonNode response;
        try {
            response = api.post().uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            Failure failure = status == 429 ? Failure.RATE_LIMITED : status >= 500 ? Failure.UNAVAILABLE : Failure.REJECTED;
            if (failure == Failure.REJECTED) {
                // 400/401/403/404: wrong key, unknown model or invalid schema. The body has no prompt text in it.
                log.error("Gemini rejected the request for model {}: HTTP {} {}", model, status, abbreviate(e.getResponseBodyAsString()));
            }
            throw new ProviderException(failure, "Gemini HTTP " + status, null);
        } catch (ResourceAccessException e) {
            throw new ProviderException(Failure.UNAVAILABLE, "Gemini unreachable: " + e.getMessage(), null);
        }
        return parse(model, response);
    }

    static Result parse(String model, JsonNode response) {
        if (response == null) throw new ProviderException(Failure.BAD_RESPONSE, "Empty Gemini response", null);
        JsonNode meta = response.path("usageMetadata");
        Usage usage = new Usage(meta.path("promptTokenCount").asLong(), meta.path("cachedContentTokenCount").asLong(),
                meta.path("candidatesTokenCount").asLong() + meta.path("thoughtsTokenCount").asLong());

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

        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            text.append(part.path("text").asText(""));
        }
        if (text.isEmpty()) throw new ProviderException(Failure.BAD_RESPONSE, "Answer without text (finish " + finish + ")", usage);
        return new Result(text.toString(), finish, usage);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
