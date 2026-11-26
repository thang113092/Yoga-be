package com.company.yoga.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.yoga.content.VideoDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Calls the real Gemini API with the production prompt and schema on a made-up video. Skipped unless
 * GEMINI_SMOKE_KEY and GEMINI_SMOKE_MODEL are set; costs a fraction of a cent. No customer data is sent.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_SMOKE_KEY", matches = ".+")
class GeminiLiveSmokeTest {

    @Test
    void realGeminiReturnsAValidSuggestion() throws Exception {
        var props = new AiProperties(true, "gemini",
                new AiProperties.Gemini(System.getenv("GEMINI_SMOKE_KEY"), "https://generativelanguage.googleapis.com/v1beta", 60, 1),
                java.util.Map.of(), new AiProperties.Quota(30, 100, BigDecimal.ONE, 80), new AiProperties.Conversations(30, 60));
        var gemini = new GeminiProvider(props);
        List<VideoDto.Tag> catalog = List.of(
                new VideoDto.Tag(UUID.randomUUID(), "STRETCHING", "Giãn cơ", 0), new VideoDto.Tag(UUID.randomUUID(), "SPINE", "Cột sống", 0),
                new VideoDto.Tag(UUID.randomUUID(), "MEDITATION", "Thiền", 0), new VideoDto.Tag(UUID.randomUUID(), "BREATHING", "Hít thở", 0),
                new VideoDto.Tag(UUID.randomUUID(), "PRENATAL", "Yoga bầu", 0), new VideoDto.Tag(UUID.randomUUID(), "STRENGTH", "Sức mạnh", 0),
                new VideoDto.Tag(UUID.randomUUID(), "RELAX", "Thư giãn", 0));
        String user = "<chu_de>\n" + catalog.stream().map(t -> "- " + t.code() + ": " + t.name()).reduce("", (a, b) -> a + b + "\n")
                + "</chu_de>\n\n<video>\n{\"title\":\"Vinyasa đảo ngược: trồng chuối và tư thế cái cày\",\"description\":\"Chuỗi bài 30 phút "
                + "cường độ cao, nhiều tư thế đảo ngược và gập bụng sâu.\",\"currentLevel\":\"ALL_LEVELS\",\"durationMinutes\":30,"
                + "\"notForPregnancy\":false,\"notForHeartBp\":false,\"notForInjuries\":false,\"contraindications\":null}\n</video>";
        Prompts.Prompt prompt = Prompts.load(VideoTaggingService.PROMPT);

        AiProvider.Result result = gemini.generateJson(new AiProvider.JsonRequest(System.getenv("GEMINI_SMOKE_MODEL"), prompt.text(), user,
                VideoTaggingService.schema(catalog), 2048, null, null));

        JsonNode answer = new ObjectMapper().readTree(result.text());
        System.out.println("SMOKE answer: " + answer.toPrettyString());
        System.out.println("SMOKE usage: " + result.usage() + " finish=" + result.finishReason());
        assertThat(answer.path("level").asText()).isIn(VideoTaggingService.LEVELS);
        assertThat(answer.path("description").asText()).isNotBlank();
        assertThat(answer.path("notForPregnancy").isBoolean()).isTrue();
        assertThat(result.usage().inputTokens()).isPositive();
    }

    /** Two rounds with a made-up revenue function: the model must call it, then answer from its result. */
    @Test
    void realGeminiCallsAToolAndAnswersFromIt() {
        var props = new AiProperties(true, "gemini",
                new AiProperties.Gemini(System.getenv("GEMINI_SMOKE_KEY"), "https://generativelanguage.googleapis.com/v1beta", 60, 1),
                java.util.Map.of(), new AiProperties.Quota(30, 100, BigDecimal.ONE, 80), new AiProperties.Conversations(30, 60));
        var gemini = new GeminiProvider(props);
        String model = System.getenv().getOrDefault("GEMINI_SMOKE_CHAT_MODEL", System.getenv("GEMINI_SMOKE_MODEL"));
        var tool = new AiProvider.ToolSpec("get_revenue_report", "Doanh thu thực thu (VNĐ) trong khoảng ngày",
                java.util.Map.of("type", "object", "properties", java.util.Map.of(
                        "startDate", java.util.Map.of("type", "string", "description", "YYYY-MM-DD"),
                        "endDate", java.util.Map.of("type", "string", "description", "YYYY-MM-DD")), "required", List.of("startDate", "endDate")));
        String system = Prompts.load("manager-assistant").text();
        var history = new java.util.ArrayList<AiProvider.Turn>();
        history.add(AiProvider.Turn.user("[Ngữ cảnh: hôm nay là 2026-11-26, giờ Việt Nam; người hỏi là chủ chuỗi]\nDoanh thu tháng 10/2026 là bao nhiêu?"));

        var first = gemini.chat(new AiProvider.ChatRequest(model, system, List.copyOf(history), List.of(tool), true, 2048, null, null));
        System.out.println("SMOKE call: " + first.turn().calls() + " usage=" + first.usage());
        assertThat(first.turn().calls()).isNotEmpty();
        var call = first.turn().calls().get(0);
        assertThat(call.name()).isEqualTo("get_revenue_report");
        assertThat(call.args()).containsEntry("startDate", "2026-10-01").containsEntry("endDate", "2026-10-31");

        history.add(first.turn());
        history.add(AiProvider.Turn.tool(List.of(new AiProvider.ToolResult(call.id(), call.name(),
                java.util.Map.of("totalRevenue", 125_400_000, "orders", 87)))));
        var second = gemini.chat(new AiProvider.ChatRequest(model, system, List.copyOf(history), List.of(tool), true, 2048, null, null));
        System.out.println("SMOKE answer: " + second.turn().text() + " usage=" + second.usage());
        assertThat(second.turn().calls()).isEmpty();
        assertThat(second.turn().text()).contains("125.400.000");
    }
}
