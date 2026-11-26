package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Assistant for students (phase 3): classes, passes, bookings, courses, videos; offers booking, never books. */
@Component
public class StudentProfile implements AssistantProfile {

    static final String KIND = "STUDENT_ASSISTANT";
    static final String PROMPT = "student-assistant";

    private final StudentTools tools;
    private final NamedParameterJdbcTemplate jdbc;

    public StudentProfile(StudentTools tools, NamedParameterJdbcTemplate jdbc) {
        this.tools = tools;
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String featureKey() {
        return AiProperties.STUDENT_CHAT;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    @Override
    public boolean accepts(String role) {
        return "STUDENT".equals(role);
    }

    @Override
    public boolean staff() {
        return false;
    }

    /** Date and the student's home branch (to read "ở đây", "chi nhánh của tôi"); no name or other personal data. */
    @Override
    public String context(Caller caller) {
        LocalDate today = LocalDate.now(StudentTools.VIETNAM);
        String home = caller.homeBranchId() == null ? null : jdbc.queryForList("SELECT name FROM yoga.branches WHERE id=:id",
                Map.of("id", caller.homeBranchId()), String.class).stream().findFirst().orElse(null);
        return "[Ngữ cảnh: hôm nay là " + ManagerProfile.DAY.format(today) + " (" + today + "), giờ Việt Nam; người hỏi là học viên"
                + (home == null ? "" : ", chi nhánh đăng ký: " + home) + "]";
    }

    @Override
    public List<AiTool> tools() {
        return tools.tools();
    }
}
