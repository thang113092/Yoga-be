package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProvider.ToolSpec;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Health-aware video suggestions for students who consented ({@link HealthConsentService}). Safety is decided here,
 * not by the model: videos the staff marked unsuitable for the student's condition are removed before the model sees
 * the list. Only three yes/no items are read from the health profile.
 */
@Component
public class HealthTools {

    static final int SHOWN = 10;

    record Flags(boolean profile, boolean injuries, boolean heartBp, boolean pregnant) {
    }

    private final HealthConsentService consents;
    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public HealthTools(HealthConsentService consents, AccessPolicy accessPolicy, NamedParameterJdbcTemplate jdbc) {
        this.consents = consents;
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
    }

    public List<AiTool> tools() {
        return List.of(myHealthFlags(), recommendVideos());
    }

    /** The consenting student; refuses anyone else and anyone who has turned consent off. */
    UUID consentingStudent() {
        UserEntity actor = accessPolicy.actor();
        if (!"STUDENT".equals(accessPolicy.role(actor))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        if (!consents.consented(actor.getId())) {
            throw new IllegalArgumentException("Học viên chưa bật 'Gợi ý theo sức khỏe'; hãy hướng dẫn bật trong khung chat");
        }
        return actor.getId();
    }

    Flags flags(UUID student) {
        List<Flags> rows = jdbc.query("SELECT has_injuries,has_heart_or_bp,is_pregnant FROM yoga.student_medical_profiles WHERE student_id=:me",
                Map.of("me", student), (rs, i) -> new Flags(true, rs.getBoolean("has_injuries"), rs.getBoolean("has_heart_or_bp"),
                        rs.getBoolean("is_pregnant")));
        return rows.isEmpty() ? new Flags(false, false, false, false) : rows.get(0);
    }

    AiTool myHealthFlags() {
        return tool(new ToolSpec("get_my_health_flags",
                "Ba mục có/không trong hồ sơ sức khỏe của học viên (đang chấn thương, bệnh tim/huyết áp, đang mang thai). "
                        + "Chỉ dùng để chọn bài phù hợp; không chẩn đoán.", null),
                args -> {
                    Flags f = flags(consentingStudent());
                    if (!f.profile()) return Map.of("profile", false, "note", "Chưa có hồ sơ sức khỏe; hướng dẫn học viên điền ở Của Tôi → Hồ Sơ Sức Khỏe");
                    return row("profile", true, "injuries", f.injuries(), "heartOrBloodPressure", f.heartBp(), "pregnant", f.pregnant());
                });
    }

    AiTool recommendVideos() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "Từ khóa, ví dụ lưng, hông, thư giãn"));
        props.put("level", Map.of("type", "string", "enum", List.of("BEGINNER", "INTERMEDIATE", "ADVANCED", "ALL_LEVELS")));
        props.put("tag", Map.of("type", "string", "description", "Mã chủ đề, ví dụ STRETCHING, SPINE, MEDITATION, BREATHING, PRENATAL, RELAX"));
        props.put("maxMinutes", Map.of("type", "integer"));
        return tool(new ToolSpec("recommend_videos_for_me",
                "Video phù hợp với hồ sơ sức khỏe của học viên: đã loại các video được đánh dấu không phù hợp với tình trạng của họ. "
                        + "Chỉ giới thiệu video trong danh sách này khi học viên hỏi bài tập theo sức khỏe.",
                Map.of("type", "object", "properties", props)),
                args -> {
                    UUID me = consentingStudent();
                    Flags f = flags(me);
                    Integer minutes = args.get("maxMinutes") instanceof Number n ? n.intValue() : null;
                    String q = ManagerReportTools.string(args.get("query"));
                    var params = new MapSqlParameterSource("me", me).addValue("level", ManagerReportTools.string(args.get("level")))
                            .addValue("tag", ManagerReportTools.string(args.get("tag")))
                            .addValue("maxSeconds", minutes == null || minutes <= 0 ? null : minutes * 60)
                            .addValue("q", q == null ? null : "%" + q + "%");
                    List<Map<String, Object>> all = jdbc.query("""
                            SELECT v.title,v.level,v.duration_seconds,v.contraindications,v.not_for_pregnancy,v.not_for_heart_bp,
                              v.not_for_injuries,yoga.has_video_access(:me,v.id) AS accessible,
                              (SELECT array_agg(t.code ORDER BY t.code) FROM yoga.video_lesson_tags x JOIN yoga.video_tags t ON t.id=x.tag_id
                                 WHERE x.video_id=v.id) AS tags
                            FROM yoga.video_lessons v
                            WHERE v.status='PUBLISHED' AND v.course_class_id IS NULL
                              AND (CAST(:level AS VARCHAR) IS NULL OR v.level=:level)
                              AND (CAST(:maxSeconds AS INTEGER) IS NULL OR v.duration_seconds<=:maxSeconds)
                              AND (CAST(:q AS VARCHAR) IS NULL OR v.title ILIKE :q OR v.description ILIKE :q)
                              AND (CAST(:tag AS VARCHAR) IS NULL OR EXISTS(SELECT 1 FROM yoga.video_lesson_tags x JOIN yoga.video_tags t
                                     ON t.id=x.tag_id WHERE x.video_id=v.id AND t.code=:tag))
                            ORDER BY v.published_at DESC LIMIT 80""", params, (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("title", rs.getString("title"));
                        m.put("level", rs.getString("level"));
                        int seconds = rs.getInt("duration_seconds");
                        m.put("minutes", rs.wasNull() ? null : Math.round(seconds / 60.0));
                        m.put("tags", tags(rs.getArray("tags")));
                        m.put("locked", !rs.getBoolean("accessible"));
                        m.put("safetyNote", rs.getString("contraindications"));
                        m.put("_pregnancy", rs.getBoolean("not_for_pregnancy"));
                        m.put("_heart", rs.getBoolean("not_for_heart_bp"));
                        m.put("_injuries", rs.getBoolean("not_for_injuries"));
                        return m;
                    });
                    List<Map<String, Object>> suitable = new ArrayList<>();
                    int excluded = 0;
                    for (Map<String, Object> v : all) {
                        boolean unsuitable = (f.pregnant() && Boolean.TRUE.equals(v.get("_pregnancy")))
                                || (f.heartBp() && Boolean.TRUE.equals(v.get("_heart")))
                                || (f.injuries() && Boolean.TRUE.equals(v.get("_injuries")));
                        v.remove("_pregnancy");
                        v.remove("_heart");
                        v.remove("_injuries");
                        if (unsuitable) excluded++;
                        else if (suitable.size() < SHOWN) suitable.add(v);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("videos", suitable);
                    out.put("excludedAsUnsuitable", excluded);
                    out.put("profile", f.profile());
                    out.put("reminder", "Nhắc học viên hỏi huấn luyện viên hoặc bác sĩ trước khi tập; dừng lại nếu đau hoặc khó chịu.");
                    return out;
                });
    }

    private static List<String> tags(Array array) throws SQLException {
        if (array == null) return List.of();
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    static Map<String, Object> row(Object... kv) {
        return ManagerReportTools.row(kv);
    }

    private static AiTool tool(ToolSpec spec, Function<Map<String, Object>, Map<String, Object>> body) {
        return new AiTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public Map<String, Object> run(Map<String, Object> args) {
                return body.apply(args == null ? Map.of() : args);
            }
        };
    }
}
