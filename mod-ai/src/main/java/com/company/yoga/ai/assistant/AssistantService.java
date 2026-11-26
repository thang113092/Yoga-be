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
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Conversations with an assistant that answers by calling read-only functions. The caller's role chooses the
 * {@link AssistantProfile} (manager or student): its model, prompt and tools. One question may take several provider
 * calls (tool rounds); the new turns are stored together only when the answer is complete, so a failed question
 * leaves the conversation as it was. Provider calls run outside database transactions.
 */
@Service
@Slf4j
public class AssistantService {

    public static final int MAX_QUESTION = 2000;
    static final int TITLE_LENGTH = 60;
    /** Key a tool result may carry to offer the user a button (e.g. book this class); the assistant never acts itself. */
    public static final String ACTION = "action";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    public record Conversation(UUID id, String title, Instant createdAt, Instant lastMessageAt) {
    }

    public record Message(String role, String text, List<String> toolsUsed, List<Map<String, Object>> actions, Instant createdAt) {
    }

    public record Answer(UUID conversationId, String title, Message message) {
    }

    private final AiProperties properties;
    private final AiProvider provider;
    private final AiUsageService usage;
    private final Prompts prompts;
    private final List<AssistantProfile> profiles;
    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public AssistantService(AiProperties properties, AiProvider provider, AiUsageService usage, Prompts prompts,
                            List<AssistantProfile> profiles, AccessPolicy accessPolicy, NamedParameterJdbcTemplate jdbc,
                            TransactionTemplate transactions, ObjectMapper json) {
        this.properties = properties;
        this.provider = provider;
        this.usage = usage;
        this.prompts = prompts;
        this.profiles = List.copyOf(profiles);
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    /** Whether the assistant of this feature (manager-chat, student-chat) is configured. */
    public boolean available(String featureKey) {
        return properties.isOn(featureKey) && provider.isConfigured();
    }

    record Session(AssistantProfile profile, AssistantProfile.Caller caller) {
    }

    /** The profile for the signed-in user's role; other roles (receptionist, instructor) have no assistant. */
    Session session() {
        UserEntity actor = accessPolicy.actor();
        String role = accessPolicy.role(actor);
        AssistantProfile profile = profiles.stream().filter(p -> p.accepts(role)).findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        return new Session(profile, new AssistantProfile.Caller(actor.getId(), role, actor.getHomeBranchId()));
    }

    // ---------------------------------------------------------------- conversations

    public List<Conversation> conversations() {
        Session s = session();
        return jdbc.query("""
                SELECT id,title,created_at,last_message_at FROM yoga.ai_conversations
                WHERE user_id=:me AND kind=:kind ORDER BY last_message_at DESC LIMIT 30""",
                new MapSqlParameterSource("me", s.caller().userId()).addValue("kind", s.profile().kind()),
                (rs, i) -> new Conversation(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at").toInstant()));
    }

    public Conversation create() {
        Session s = session();
        if (!available(s.profile().featureKey())) throw new BusinessException(AiResultCodes.DISABLED);
        return jdbc.queryForObject("""
                INSERT INTO yoga.ai_conversations(user_id,kind,title) VALUES(:me,:kind,'Hội thoại mới')
                RETURNING id,title,created_at,last_message_at""",
                new MapSqlParameterSource("me", s.caller().userId()).addValue("kind", s.profile().kind()),
                (rs, i) -> new Conversation(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at").toInstant()));
    }

    public void delete(UUID conversationId) {
        Session s = session();
        int deleted = jdbc.update("DELETE FROM yoga.ai_conversations WHERE id=:id AND user_id=:me AND kind=:kind",
                new MapSqlParameterSource("id", conversationId).addValue("me", s.caller().userId()).addValue("kind", s.profile().kind()));
        if (deleted == 0) throw new BusinessException(AiResultCodes.CONVERSATION_NOT_FOUND);
    }

    /** What the user sees: questions and answers, with the functions used and the buttons offered for each answer. */
    public List<Message> messages(UUID conversationId) {
        Session s = session();
        owned(conversationId, s);
        List<Message> out = new ArrayList<>();
        Set<String> tools = new LinkedHashSet<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Stored st : load(conversationId)) {
            Turn t = st.turn();
            switch (t.role()) {
                case USER -> {
                    tools.clear();
                    actions.clear();
                    out.add(new Message("USER", st.question(), List.of(), List.of(), st.createdAt()));
                }
                case MODEL -> {
                    t.calls().forEach(c -> tools.add(c.name()));
                    if (t.text() != null && !t.text().isBlank()) {
                        out.add(new Message("MODEL", t.text(), List.copyOf(tools), List.copyOf(actions), st.createdAt()));
                    }
                }
                case TOOL -> t.results().forEach(r -> actions.addAll(actions(r.response())));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- questions

    public Answer ask(UUID conversationId, String question) {
        Session s = session();
        if (!available(s.profile().featureKey())) throw new BusinessException(AiResultCodes.DISABLED);
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        String title = owned(conversationId, s);
        if (!inFlight.add(conversationId)) throw new BusinessException(AiResultCodes.CONVERSATION_BUSY);
        try {
            List<Stored> stored = load(conversationId);
            if (stored.size() + 3 > properties.conversations().maxMessages()) throw new BusinessException(AiResultCodes.CONVERSATION_FULL);
            usage.checkQuota(s.caller().userId(), s.profile().staff());
            return run(conversationId, title, stored, q, s);
        } finally {
            inFlight.remove(conversationId);
        }
    }

    private Answer run(UUID conversationId, String title, List<Stored> stored, String question, Session s) {
        AssistantProfile profile = s.profile();
        UUID me = s.caller().userId();
        AiProperties.Feature feature = properties.feature(profile.featureKey());
        Prompts.Prompt prompt = prompts.get(profile.prompt());
        Map<String, AiTool> tools = new LinkedHashMap<>();
        profile.tools().forEach(t -> tools.put(t.spec().name(), t));
        List<AiProvider.ToolSpec> specs = tools.values().stream().map(AiTool::spec).toList();

        List<Turn> history = new ArrayList<>(stored.stream().map(Stored::turn).toList());
        List<Pending> added = new ArrayList<>();
        Turn userTurn = Turn.user(profile.context(s.caller()) + "\n" + question);
        history.add(userTurn);
        added.add(new Pending(userTurn, question, null));

        Set<String> used = new LinkedHashSet<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        String answer = null;
        for (int round = 0; round <= feature.maxToolRounds() && answer == null; round++) {
            boolean allowTools = round < feature.maxToolRounds();
            AiProvider.ChatResult result;
            try {
                result = provider.chat(new AiProvider.ChatRequest(feature.model(), prompt.text(), List.copyOf(history), specs, allowTools,
                        feature.maxOutputTokens(), feature.thinkingLevel(), feature.thinkingBudget()));
            } catch (AiProvider.ProviderException e) {
                if (e.usage().inputTokens() > 0 || e.usage().outputTokens() > 0) usage.record(me, feature, e.usage(), round == 0);
                throw new BusinessException(switch (e.failure()) {
                    case BLOCKED -> AiResultCodes.BLOCKED;
                    case TRUNCATED -> AiResultCodes.TRUNCATED;
                    case RATE_LIMITED, UNAVAILABLE -> AiResultCodes.BUSY;
                    default -> AiResultCodes.PROVIDER_ERROR;
                });
            }
            usage.record(me, feature, result.usage(), round == 0);
            Turn model = result.turn();
            if (!allowTools && !model.calls().isEmpty()) {
                // Calls without results would break the next question: keep only the text of a forced last turn.
                model = new Turn(Role.MODEL, model.text(), List.of(), List.of(), null);
            }
            history.add(model);
            added.add(new Pending(model, null, result));

            if (model.calls().isEmpty()) {
                answer = model.text() == null || model.text().isBlank()
                        ? "Xin lỗi, tôi chưa trả lời được câu này. Bạn thử hỏi cụ thể hơn nhé." : model.text();
                break;
            }
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : model.calls()) {
                used.add(call.name());
                Map<String, Object> response = runTool(tools.get(call.name()), call);
                actions.addAll(actions(response));
                results.add(new ToolResult(call.id(), call.name(), response));
            }
            Turn toolTurn = Turn.tool(results);
            history.add(toolTurn);
            added.add(new Pending(toolTurn, null, null));
        }
        if (answer == null) throw new BusinessException(AiResultCodes.PROVIDER_ERROR);

        String newTitle = stored.isEmpty() ? title(question) : title;
        save(conversationId, stored.size(), added, newTitle, feature.model());
        return new Answer(conversationId, newTitle, new Message("MODEL", answer, List.copyOf(used), List.copyOf(actions), Instant.now()));
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
            return Map.of("error", "Hệ thống lỗi khi lấy dữ liệu; hãy báo người dùng thử lại sau");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actions(Map<String, Object> response) {
        return response != null && response.get(ACTION) instanceof Map<?, ?> m ? List.of((Map<String, Object>) m) : List.of();
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

    private String owned(UUID conversationId, Session s) {
        List<String> titles = jdbc.queryForList("SELECT title FROM yoga.ai_conversations WHERE id=:id AND user_id=:me AND kind=:kind",
                new MapSqlParameterSource("id", conversationId).addValue("me", s.caller().userId()).addValue("kind", s.profile().kind()),
                String.class);
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

    private void save(UUID conversationId, int alreadyStored, List<Pending> added, String title, String model) {
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
                                .addValue("model", r == null ? null : model)
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
