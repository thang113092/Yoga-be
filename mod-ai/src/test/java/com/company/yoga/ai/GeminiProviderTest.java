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

    // ---------------------------------------------------------------- chat with tools

    private static final AiProvider.ToolSpec BRANCHES = new AiProvider.ToolSpec("list_branches", "Danh sách chi nhánh", null);
    private static final AiProvider.ToolSpec REVENUE = new AiProvider.ToolSpec("get_revenue_report", "Doanh thu",
            Map.of("type", "object", "properties", Map.of("startDate", Map.of("type", "string")), "required", List.of("startDate")));

    @Test
    void chatDeclaresToolsAndReplaysEarlierTurnsVerbatim() throws Exception {
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                [{"functionCall":{"id":"c1","name":"list_branches","args":{}},"thoughtSignature":"sig-1"}]""");
        var history = List.of(
                AiProvider.Turn.user("Doanh thu?"),
                new AiProvider.Turn(AiProvider.Role.MODEL, "", List.of(new AiProvider.ToolCall("c1", "list_branches", Map.of())), List.of(), raw),
                AiProvider.Turn.tool(List.of(new AiProvider.ToolResult("c1", "list_branches", Map.of("branches", List.of())))));
        server.expect(requestTo(URL)).andExpect(content().json("""
                {"systemInstruction":{"parts":[{"text":"Trợ lý"}]},
                 "contents":[{"role":"user","parts":[{"text":"Doanh thu?"}]},
                   {"role":"model","parts":[{"functionCall":{"id":"c1","name":"list_branches","args":{}},"thoughtSignature":"sig-1"}]},
                   {"role":"user","parts":[{"functionResponse":{"id":"c1","name":"list_branches","response":{"branches":[]}}}]}],
                 "tools":[{"functionDeclarations":[{"name":"list_branches","description":"Danh sách chi nhánh"},
                   {"name":"get_revenue_report","description":"Doanh thu",
                    "parameters":{"type":"OBJECT","properties":{"startDate":{"type":"STRING"}},"required":["startDate"]}}]}],
                 "toolConfig":{"functionCallingConfig":{"mode":"AUTO"}},
                 "generationConfig":{"maxOutputTokens":1024}}""", true))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"Doanh thu là **1.000.000 đ**."}]},"finishReason":"STOP"}],
                         "usageMetadata":{"promptTokenCount":500,"candidatesTokenCount":20}}""", MediaType.APPLICATION_JSON));

        var result = gemini.chat(new AiProvider.ChatRequest("test-model", "Trợ lý", history, List.of(BRANCHES, REVENUE), true, 1024, null, null));

        assertThat(result.turn().text()).isEqualTo("Doanh thu là **1.000.000 đ**.");
        assertThat(result.turn().calls()).isEmpty();
        assertThat(result.usage()).isEqualTo(new AiProvider.Usage(500, 0, 20));
        server.verify();
    }

    @Test
    void chatReturnsCallsAndKeepsTheRawPartsWithSignatures() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                   {"text":"plan","thought":true},
                   {"functionCall":{"name":"get_revenue_report","args":{"startDate":"2026-10-01","endDate":"2026-10-31"}},"thoughtSignature":"abc"}]},
                  "finishReason":"STOP"}]}""", MediaType.APPLICATION_JSON));
        var result = gemini.chat(new AiProvider.ChatRequest("test-model", "s", List.of(AiProvider.Turn.user("q")), List.of(REVENUE), true, 512, "low", null));
        assertThat(result.turn().text()).isEmpty();
        assertThat(result.turn().calls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("get_revenue_report");
            assertThat(c.args()).containsEntry("endDate", "2026-10-31");
        });
        assertThat(result.turn().raw().toString()).contains("\"thoughtSignature\":\"abc\"").contains("\"thought\":true");
    }

    @Test
    void lastRoundForbidsTools() {
        assertThat(GeminiProvider.body(new AiProvider.ChatRequest("m", "s", List.of(AiProvider.Turn.user("q")), List.of(REVENUE), false, 512, null, null)))
                .containsEntry("toolConfig", Map.of("functionCallingConfig", Map.of("mode", "NONE")));
    }

    @Test
    void malformedCallIsABadResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL"}]}""", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gemini.chat(new AiProvider.ChatRequest("test-model", "s", List.of(AiProvider.Turn.user("q")), List.of(REVENUE), true, 512, null, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.failure()).isEqualTo(Failure.BAD_RESPONSE));
    }
}
