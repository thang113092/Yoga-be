package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProperties;
import com.company.yoga.ai.AiProvider;
import com.company.yoga.ai.AiProvider.Role;
import com.company.yoga.ai.AiProvider.ToolCall;
import com.company.yoga.ai.AiProvider.ToolResult;
import com.company.yoga.ai.AiProvider.Turn;
import com.company.yoga.ai.AiResultCodes;
import com.company.yoga.ai.AiUsageService;
import com.company.yoga.ai.Prompts;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manager assistant: a conversation where Gemini answers from report functions. One question may take several
 * provider calls (tool rounds); the new turns are stored together only when the answer is complete, so a failed
 * question leaves the conversation as it was. Provider calls run outside database transactions.
 */
@Service
@Slf4j
public class ManagerAssistantService {

    static final String PROMPT = "manager-assistant";
    static final String KIND = "MANAGER_ASSISTANT";
    public static final int MAX_QUESTION = 2000;
    static final int TITLE_LENGTH = 60;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy", Locale.forLanguageTag("vi"));

    public record Conversation(UUID id, String title, Instant createdAt, Instant lastMessageAt) {
    }

    public record Message(String role, String text, List<String> toolsUsed, Instant createdAt) {
    }

    public record Answer(UUID conversationId, String title, Message message) {
    }

    private final AiProperties properties;
    private final AiProvider provider;
    private final AiUsageService usage;
    private final Prompts prompts;
    private final ManagerReportTools reports;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public ManagerAssistantService(AiProperties properties, AiProvider provider, AiUsageService usage, Prompts prompts,
                                   ManagerReportTools reports, NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                                   ObjectMapper json) {
        this.properties = properties;
        this.provider = provider;
        this.usage = usage;
        this.prompts = prompts;
        this.reports = reports;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    public boolean available() {
        return properties.isOn(AiProperties.MANAGER_CHAT) && provider.isConfigured();
    }

    // ---------------------------------------------------------------- conversations

    public List<Conversation> conversations() {
        ManagerReportTools.Scope me = reports.scope();
        return jdbc.query("""
                SELECT id,title,created_at,last_message_at FROM yoga.ai_conversations
                WHERE user_id=:me AND kind=:kind ORDER BY last_message_at DESC LIMIT 30""",
                new MapSqlParameterSource("me", me.userId()).addValue("kind", KIND),
                (rs, i) -> new Conversation(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at").toInstant()));
    }

    public Conversation create() {
        if (!available()) throw new BusinessException(AiResultCodes.DISABLED);
        ManagerReportTools.Scope me = reports.scope();
        return jdbc.queryForObject("""
                INSERT INTO yoga.ai_conversations(user_id,kind,title) VALUES(:me,:kind,'Hội thoại mới')
                RETURNING id,title,created_at,last_message_at""",
                new MapSqlParameterSource("me", me.userId()).addValue("kind", KIND),
                (rs, i) -> new Conversation(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at").toInstant()));
    }

    public void delete(UUID conversationId) {
        UUID me = reports.scope().userId();
        int deleted = jdbc.update("DELETE FROM yoga.ai_conversations WHERE id=:id AND user_id=:me AND kind=:kind",
                new MapSqlParameterSource("id", conversationId).addValue("me", me).addValue("kind", KIND));
        if (deleted == 0) throw new BusinessException(AiResultCodes.CONVERSATION_NOT_FOUND);
    }

    /** What the user sees: questions and answers, not tool traffic. */
    public List<Message> messages(UUID conversationId) {
        owned(conversationId, reports.scope().userId());
        List<Message> out = new ArrayList<>();
        Set<String> tools = new LinkedHashSet<>();
        for (Stored s : load(conversationId)) {
            Turn t = s.turn();
            if (t.role() == Role.USER) {
                tools.clear();
                out.add(new Message("USER", s.question(), List.of(), s.createdAt()));
            } else if (t.role() == Role.MODEL) {
                t.calls().forEach(c -> tools.add(c.name()));
                if (t.text() != null && !t.text().isBlank()) {
                    out.add(new Message("MODEL", t.text(), List.copyOf(tools), s.createdAt()));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- questions

    public Answer ask(UUID conversationId, String question) {
        if (!available()) throw new BusinessException(AiResultCodes.DISABLED);
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        ManagerReportTools.Scope me = reports.scope();
        String title = owned(conversationId, me.userId());
        if (!inFlight.add(conversationId)) throw new BusinessException(AiResultCodes.CONVERSATION_BUSY);
        try {
            List<Stored> stored = load(conversationId);
            if (stored.size() + 3 > properties.conversations().maxMessages()) throw new BusinessException(AiResultCodes.CONVERSATION_FULL);
            usage.checkQuota(me.userId(), true);
            return run(conversationId, title, stored, q, me);
        } finally {
            inFlight.remove(conversationId);
        }
    }

    private Answer run(UUID conversationId, String title, List<Stored> stored, String question, ManagerReportTools.Scope me) {
        AiProperties.Feature feature = properties.feature(AiProperties.MANAGER_CHAT);
        Prompts.Prompt prompt = prompts.get(PROMPT);
        Map<String, AiTool> tools = new LinkedHashMap<>();
        reports.tools().forEach(t -> tools.put(t.spec().name(), t));
        List<AiProvider.ToolSpec> specs = tools.values().stream().map(AiTool::spec).toList();

        List<Turn> history = new ArrayList<>(stored.stream().map(Stored::turn).toList());
        List<Pending> added = new ArrayList<>();
        Turn userTurn = Turn.user(context(me) + "\n" + question);
        history.add(userTurn);
        added.add(new Pending(userTurn, question, null));

        Set<String> used = new LinkedHashSet<>();
        String answer = null;
        for (int round = 0; round <= feature.maxToolRounds() && answer == null; round++) {
            boolean allowTools = round < feature.maxToolRounds();
            AiProvider.ChatResult result;
            try {
                result = provider.chat(new AiProvider.ChatRequest(feature.model(), prompt.text(), List.copyOf(history), specs, allowTools,
                        feature.maxOutputTokens(), feature.thinkingLevel(), feature.thinkingBudget()));
            } catch (AiProvider.ProviderException e) {
                if (e.usage().inputTokens() > 0 || e.usage().outputTokens() > 0) usage.record(me.userId(), feature, e.usage(), round == 0);
                throw new BusinessException(switch (e.failure()) {
                    case BLOCKED -> AiResultCodes.BLOCKED;
                    case TRUNCATED -> AiResultCodes.TRUNCATED;
                    case RATE_LIMITED, UNAVAILABLE -> AiResultCodes.BUSY;
                    default -> AiResultCodes.PROVIDER_ERROR;
                });
            }
            usage.record(me.userId(), feature, result.usage(), round == 0);
            Turn model = result.turn();
            if (!allowTools && !model.calls().isEmpty()) {
                // Calls without results would break the next question: keep only the text of a forced last turn.
                model = new Turn(Role.MODEL, model.text(), List.of(), List.of(), null);
            }
            history.add(model);
            added.add(new Pending(model, null, result));

            if (model.calls().isEmpty() || !allowTools) {
                answer = model.text() == null || model.text().isBlank() ? "Xin lỗi, tôi chưa trả lời được câu này. Bạn thử hỏi cụ thể hơn nhé." : model.text();
                break;
            }
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : model.calls()) {
                used.add(call.name());
                results.add(new ToolResult(call.id(), call.name(), runTool(tools.get(call.name()), call)));
            }
            Turn toolTurn = Turn.tool(results);
            history.add(toolTurn);
            added.add(new Pending(toolTurn, null, null));
        }
        if (answer == null) throw new BusinessException(AiResultCodes.PROVIDER_ERROR);

        String newTitle = stored.isEmpty() ? title(question) : title;
        save(conversationId, stored.size(), added, newTitle);
        return new Answer(conversationId, newTitle, new Message("MODEL", answer, List.copyOf(used), Instant.now()));
    }

    /** Runs one tool; refusals and bad arguments go back to the model as an error it can explain. */
    Map<String, Object> runTool(AiTool tool, ToolCall call) {
        if (tool == null) return Map.of("error", "Không có hàm " + call.name());
        try {
            return tool.run(call.args());
        } catch (BusinessException e) {
            return Map.of("error", e.getMessage() == null ? "Không có quyền hoặc dữ liệu không hợp lệ" : e.getMessage());
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return Map.of("error", "Không có quyền xem số liệu này");
        } catch (RuntimeException e) {
            log.error("Assistant tool {} failed", call.name(), e);
            return Map.of("error", "Hệ thống lỗi khi lấy số liệu; hãy báo người dùng thử lại sau");
        }
    }

    /** Today's date and the asker's scope, at the start of each question (not in the system prompt, to keep it cacheable). */
    static String context(ManagerReportTools.Scope me) {
        LocalDate today = LocalDate.now(ManagerReportTools.VIETNAM);
        return "[Ngữ cảnh: hôm nay là " + DAY.format(today) + " (" + today + "), giờ Việt Nam; người hỏi là "
                + (me.superAdmin() ? "chủ chuỗi, xem được mọi chi nhánh" : "quản lý chi nhánh, chỉ xem được chi nhánh của mình") + "]";
    }

    static String title(String question) {
        String oneLine = question.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= TITLE_LENGTH ? oneLine : oneLine.substring(0, TITLE_LENGTH - 1).trim() + "…";
    }

    // ---------------------------------------------------------------- storage

    record Stored(Turn turn, String question, Instant createdAt) {
    }

    record Pending(Turn turn, String question, AiProvider.ChatResult result) {
    }

    private String owned(UUID conversationId, UUID me) {
        List<String> titles = jdbc.queryForList("SELECT title FROM yoga.ai_conversations WHERE id=:id AND user_id=:me AND kind=:kind",
                new MapSqlParameterSource("id", conversationId).addValue("me", me).addValue("kind", KIND), String.class);
        if (titles.isEmpty()) throw new BusinessException(AiResultCodes.CONVERSATION_NOT_FOUND);
        return titles.get(0);
    }

    List<Stored> load(UUID conversationId) {
        return jdbc.query("SELECT role,content,created_at FROM yoga.ai_messages WHERE conversation_id=:id ORDER BY seq",
                new MapSqlParameterSource("id", conversationId), (rs, i) -> {
                    JsonNode c = read(rs.getString("content"));
                    Role role = Role.valueOf(rs.getString("role"));
                    Instant at = rs.getTimestamp("created_at").toInstant();
                    return switch (role) {
                        case USER -> new Stored(Turn.user(c.path("text").asText("")), c.path("question").asText(""), at);
                        case MODEL -> new Stored(new Turn(Role.MODEL, c.path("text").asText(""), calls(c.path("calls")), List.of(),
                                c.hasNonNull("raw") ? c.get("raw") : null), null, at);
                        case TOOL -> new Stored(Turn.tool(results(c.path("results"))), null, at);
                    };
                });
    }

    private void save(UUID conversationId, int alreadyStored, List<Pending> added, String title) {
        transactions.executeWithoutResult(tx -> {
            int seq = alreadyStored;
            for (Pending p : added) {
                seq++;
                AiProvider.ChatResult r = p.result();
                jdbc.update("""
                        INSERT INTO yoga.ai_messages(conversation_id,seq,role,content,provider,model,input_tokens,output_tokens,finish_reason)
                        VALUES(:c,:seq,:role,CAST(:content AS JSONB),:provider,:model,:input,:output,:finish)""",
                        new MapSqlParameterSource("c", conversationId).addValue("seq", seq).addValue("role", p.turn().role().name())
                                .addValue("content", write(content(p)))
                                .addValue("provider", r == null ? null : provider.name())
                                .addValue("model", r == null ? null : properties.feature(AiProperties.MANAGER_CHAT).model())
                                .addValue("input", r == null ? 0 : (int) Math.min(Integer.MAX_VALUE, r.usage().inputTokens()))
                                .addValue("output", r == null ? 0 : (int) Math.min(Integer.MAX_VALUE, r.usage().outputTokens()))
                                .addValue("finish", r == null ? null : r.finishReason()));
            }
            jdbc.update("UPDATE yoga.ai_conversations SET title=:title,last_message_at=CURRENT_TIMESTAMP WHERE id=:id",
                    new MapSqlParameterSource("id", conversationId).addValue("title", title));
        });
    }

    private static Map<String, Object> content(Pending p) {
        Turn t = p.turn();
        Map<String, Object> c = new LinkedHashMap<>();
        switch (t.role()) {
            case USER -> {
                c.put("text", t.text());
                c.put("question", p.question());
            }
            case MODEL -> {
                c.put("text", t.text());
                c.put("calls", t.calls().stream().map(x -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", x.id());
                    m.put("name", x.name());
                    m.put("args", x.args());
                    return m;
                }).toList());
                c.put("raw", t.raw());
            }
            case TOOL -> c.put("results", t.results().stream().map(x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.id());
                m.put("name", x.name());
                m.put("response", x.response());
                return m;
            }).toList());
        }
        return c;
    }

    private List<ToolCall> calls(JsonNode node) {
        List<ToolCall> out = new ArrayList<>();
        for (JsonNode n : node) {
            out.add(new ToolCall(n.hasNonNull("id") ? n.get("id").asText() : null, n.path("name").asText(),
                    n.hasNonNull("args") ? json.convertValue(n.get("args"), MAP) : Map.of()));
        }
        return out;
    }

    private List<ToolResult> results(JsonNode node) {
        List<ToolResult> out = new ArrayList<>();
        for (JsonNode n : node) {
            out.add(new ToolResult(n.hasNonNull("id") ? n.get("id").asText() : null, n.path("name").asText(),
                    n.hasNonNull("response") ? json.convertValue(n.get("response"), MAP) : Map.of()));
        }
        return out;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored AI message is not JSON", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- retention

    /** Daily: deletes conversations untouched for longer than the retention period. */
    @Scheduled(cron = "${yoga.ai.conversations.cleanup-cron:0 30 2 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void cleanup() {
        int deleted = jdbc.update("DELETE FROM yoga.ai_conversations WHERE last_message_at < CURRENT_TIMESTAMP - make_interval(days => :days)",
                new MapSqlParameterSource("days", properties.conversations().retentionDays()));
        if (deleted > 0) log.info("Deleted {} AI conversations older than {} days", deleted, properties.conversations().retentionDays());
    }
}
