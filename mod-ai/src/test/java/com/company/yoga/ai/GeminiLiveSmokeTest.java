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

    /**
     * Student prompt with made-up classes: the model must look up the branch, search classes and propose (not book)
     * the earliest Hatha class, then tell the student to press the button.
     */
    @Test
    void realGeminiProposesABookingForAStudent() {
        var props = new AiProperties(true, "gemini",
                new AiProperties.Gemini(System.getenv("GEMINI_SMOKE_KEY"), "https://generativelanguage.googleapis.com/v1beta", 60, 1),
                java.util.Map.of(), new AiProperties.Quota(30, 100, BigDecimal.ONE, 80), new AiProperties.Conversations(30, 60));
        var gemini = new GeminiProvider(props);
        String model = System.getenv().getOrDefault("GEMINI_SMOKE_CHAT_MODEL", System.getenv("GEMINI_SMOKE_MODEL"));
        java.util.Map<String, Object> obj = java.util.Map.of("type", "object");
        var specs = List.of(
                new AiProvider.ToolSpec("list_branches", "Các chi nhánh (id, tên)", null),
                new AiProvider.ToolSpec("search_classes", "Tìm buổi lớp sắp diễn ra theo ngày, chi nhánh, bộ môn",
                        java.util.Map.of("type", "object", "properties", java.util.Map.of(
                                "fromDate", java.util.Map.of("type", "string", "description", "YYYY-MM-DD"),
                                "branchId", java.util.Map.of("type", "string"), "classType", java.util.Map.of("type", "string")),
                                "required", List.of("fromDate"))),
                new AiProvider.ToolSpec("get_my_passes", "Thẻ tập của học viên", null),
                new AiProvider.ToolSpec("propose_booking", "Hiện nút Đặt chỗ cho một buổi; KHÔNG đặt chỗ",
                        java.util.Map.of("type", "object", "properties", java.util.Map.of("scheduleId", java.util.Map.of("type", "string")),
                                "required", List.of("scheduleId"))));
        java.util.Map<String, java.util.Map<String, Object>> fake = java.util.Map.of(
                "list_branches", java.util.Map.of("branches", List.of(java.util.Map.of("id", "b-cg", "name", "Cầu Giấy"),
                        java.util.Map.of("id", "b-q1", "name", "Quận 1"))),
                "search_classes", java.util.Map.of("classes", List.of(
                        java.util.Map.of("scheduleId", "s-1930", "className", "Hatha cơ bản", "branch", "Cầu Giấy", "startAt", "thứ Năm 26/11/2026 19:30", "seatsLeft", 2),
                        java.util.Map.of("scheduleId", "s-1800", "className", "Hatha cơ bản", "branch", "Cầu Giấy", "startAt", "thứ Năm 26/11/2026 18:00", "seatsLeft", 5))),
                "get_my_passes", java.util.Map.of("passes", List.of(java.util.Map.of("plan", "Thẻ 1 tháng", "status", "ACTIVE",
                        "remainingSessions", "không giới hạn", "branches", "Tất cả chi nhánh", "access", "IN_PERSON"))),
                "propose_booking", java.util.Map.of("ok", true, "note", "Đã hiện nút Đặt chỗ; chưa đặt chỗ."));
        String system = Prompts.load("student-assistant").text();
        var history = new java.util.ArrayList<AiProvider.Turn>();
        history.add(AiProvider.Turn.user("[Ngữ cảnh: hôm nay là thứ Năm 26/11/2026 (2026-11-26), giờ Việt Nam; người hỏi là học viên]\n"
                + "Tối nay ở Cầu Giấy có lớp Hatha không? Đặt giúp mình lớp sớm nhất nhé."));
        String proposed = null;
        String answer = null;
        for (int round = 0; round < 6 && answer == null; round++) {
            var r = gemini.chat(new AiProvider.ChatRequest(model, system, List.copyOf(history), specs, round < 5, 2048, null, null));
            history.add(r.turn());
            System.out.println("SMOKE student round " + round + ": calls=" + r.turn().calls() + " usage=" + r.usage());
            if (r.turn().calls().isEmpty()) {
                answer = r.turn().text();
                break;
            }
            var results = new java.util.ArrayList<AiProvider.ToolResult>();
            for (var c : r.turn().calls()) {
                if ("propose_booking".equals(c.name())) proposed = String.valueOf(c.args().get("scheduleId"));
                results.add(new AiProvider.ToolResult(c.id(), c.name(), fake.getOrDefault(c.name(), java.util.Map.of("error", "không có hàm"))));
            }
            history.add(AiProvider.Turn.tool(results));
        }
        System.out.println("SMOKE student answer: " + answer);
        assertThat(proposed).isEqualTo("s-1800");
        assertThat(answer).isNotBlank().contains("Đặt chỗ");
    }

    /** Streaming with tools and the staff prompt: a call round, then the answer arrives in several pieces. */
    @Test
    void realGeminiStreamsAStaffAnswer() {
        var props = new AiProperties(true, "gemini",
                new AiProperties.Gemini(System.getenv("GEMINI_SMOKE_KEY"), "https://generativelanguage.googleapis.com/v1beta", 60, 1),
                java.util.Map.of(), new AiProperties.Quota(30, 100, BigDecimal.ONE, 80), new AiProperties.Conversations(30, 60));
        var gemini = new GeminiProvider(props);
        String model = System.getenv().getOrDefault("GEMINI_SMOKE_CHAT_MODEL", System.getenv("GEMINI_SMOKE_MODEL"));
        var roster = new AiProvider.ToolSpec("get_class_roster", "Danh sách học viên đã đặt một buổi lớp",
                java.util.Map.of("type", "object", "properties", java.util.Map.of("scheduleId", java.util.Map.of("type", "string")),
                        "required", List.of("scheduleId")));
        var schedule = new AiProvider.ToolSpec("get_my_teaching_schedule", "Lịch dạy của huấn luyện viên",
                java.util.Map.of("type", "object", "properties", java.util.Map.of("fromDate", java.util.Map.of("type", "string")),
                        "required", List.of("fromDate")));
        java.util.Map<String, java.util.Map<String, Object>> fake = java.util.Map.of(
                "get_my_teaching_schedule", java.util.Map.of("classes", List.of(java.util.Map.of("scheduleId", "s-18", "className", "Hatha",
                        "startAt", "thứ Năm 26/11/2026 18:00", "booked", 3, "capacity", 12))),
                "get_class_roster", java.util.Map.of("total", 3, "attendees", List.of(
                        java.util.Map.of("name", "Nguyễn Lan", "phoneEnding", "***123", "status", "CONFIRMED"),
                        java.util.Map.of("name", "Trần Minh", "phoneEnding", "***456", "status", "CONFIRMED"),
                        java.util.Map.of("name", "Lê Hoa", "phoneEnding", "***789", "status", "ATTENDED"))));
        String system = Prompts.load("staff-assistant").text();
        var history = new java.util.ArrayList<AiProvider.Turn>();
        history.add(AiProvider.Turn.user("[Ngữ cảnh: hôm nay là thứ Năm 26/11/2026 (2026-11-26), giờ Việt Nam; người hỏi là huấn luyện viên]\n"
                + "Lớp tối nay của tôi có những ai?"));
        var pieces = new java.util.ArrayList<String>();
        String answer = null;
        for (int round = 0; round < 5 && answer == null; round++) {
            var r = gemini.chatStream(new AiProvider.ChatRequest(model, system, List.copyOf(history), List.of(schedule, roster), round < 4,
                    2048, null, null), pieces::add);
            history.add(r.turn());
            System.out.println("SMOKE staff round " + round + ": calls=" + r.turn().calls());
            if (r.turn().calls().isEmpty()) {
                answer = r.turn().text();
                break;
            }
            var results = new java.util.ArrayList<AiProvider.ToolResult>();
            for (var c : r.turn().calls()) results.add(new AiProvider.ToolResult(c.id(), c.name(), fake.getOrDefault(c.name(), java.util.Map.of())));
            history.add(AiProvider.Turn.tool(results));
        }
        System.out.println("SMOKE staff answer (" + pieces.size() + " pieces): " + answer);
        assertThat(answer).contains("Nguyễn Lan").contains("Lê Hoa");
        assertThat(String.join("", pieces)).endsWith(answer);
    }
}
