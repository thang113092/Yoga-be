package com.company.yoga.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.company.yoga.ai.AiProvider.Failure;
import com.company.yoga.ai.AiProvider.ProviderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiProviderTest {

    private static final String URL = "https://gemini.test/v1beta/models/test-model:generateContent";
    private static final String OK = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true},{"text":"{\\"a\\":"},{"text":"1}"}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":120,"cachedContentTokenCount":20,"candidatesTokenCount":30,"thoughtsTokenCount":10}}""";

    private MockRestServiceServer server;
    private GeminiProvider gemini;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test/v1beta");
        server = MockRestServiceServer.bindTo(builder).build();
        gemini = new GeminiProvider(builder.build(), "key-1", 2, d -> sleeps.add(d.toMillis()));
    }

    private static AiProvider.JsonRequest request(String level, Integer budget) {
        return new AiProvider.JsonRequest("test-model", "Bạn là biên tập viên", "<video>x</video>",
                Map.of("type", "object", "properties", Map.of("a", Map.of("type", "integer"))), 256, level, budget);
    }

    @Test
    void sendsKeyInHeaderAndSchemaInGeminiSpelling() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "key-1"))
                .andExpect(content().json("""
                        {"systemInstruction":{"parts":[{"text":"Bạn là biên tập viên"}]},
                         "contents":[{"role":"user","parts":[{"text":"<video>x</video>"}]}],
                         "generationConfig":{"responseMimeType":"application/json","maxOutputTokens":256,
                           "responseSchema":{"type":"OBJECT","properties":{"a":{"type":"INTEGER"}}}}}""", true))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        AiProvider.Result result = gemini.generateJson(request(null, null));
        assertThat(result.text()).isEqualTo("{\"a\":1}");
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.usage()).isEqualTo(new AiProvider.Usage(120, 20, 40));
        server.verify();
    }

    @Test
    void sendsThinkingOnlyWhenConfigured() {
        assertThat(GeminiProvider.body(request(null, null))).extractingByKey("generationConfig")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).doesNotContainKey("thinkingConfig");
        assertThat(GeminiProvider.body(request("low", 0))).extractingByKey("generationConfig")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("thinkingConfig", Map.of("thinkingLevel", "low", "thinkingBudget", 0));
    }

    @Test
    void retriesWhenOverloadedThenSucceeds() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertThat(gemini.generateJson(request(null, null)).text()).isEqualTo("{\"a\":1}");
        assertThat(sleeps).containsExactly(800L, 1600L);
        server.verify();
    }

    @Test
    void givesUpAfterMaxRetries() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> gemini.generateJson(request(null, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.failure()).isEqualTo(Failure.UNAVAILABLE));
        server.verify();
    }

    @Test
    void configurationErrorsAreNotRetried() {
        server.expect(times(1), requestTo(URL)).andRespond(withBadRequest().body("{\"error\":{\"message\":\"model not found\"}}"));
        assertThatThrownBy(() -> gemini.generateJson(request(null, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.failure()).isEqualTo(Failure.REJECTED));
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void safetyStopIsBlockedAndKeepsBilledUsage() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":0}}""",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gemini.generateJson(request(null, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.failure()).isEqualTo(Failure.BLOCKED);
                    assertThat(e.usage().inputTokens()).isEqualTo(50);
                });
    }

    @Test
    void blockedPromptHasNoCandidate() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":12}}""", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gemini.generateJson(request(null, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.failure()).isEqualTo(Failure.BLOCKED));
    }

    @Test
    void cutAnswerIsTruncated() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":"}]},"finishReason":"MAX_TOKENS"}]}""", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gemini.generateJson(request(null, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.failure()).isEqualTo(Failure.TRUNCATED));
    }

    @Test
    void refusesToCallWithoutKey() {
        var noKey = new GeminiProvider(RestClient.builder().build(), "", 0, d -> { });
        assertThat(noKey.isConfigured()).isFalse();
        assertThatThrownBy(() -> noKey.generateJson(request(null, null))).isInstanceOf(ProviderException.class);
    }
}
