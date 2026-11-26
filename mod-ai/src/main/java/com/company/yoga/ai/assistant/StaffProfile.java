package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Assistant for receptionists (branch classes, rosters, student passes) and instructors (own schedule, rosters). */
@Component
public class StaffProfile implements AssistantProfile {

    static final String KIND = "STAFF_ASSISTANT";
    static final String PROMPT = "staff-assistant";

    private final StaffTools tools;
    private final NamedParameterJdbcTemplate jdbc;

    public StaffProfile(StaffTools tools, NamedParameterJdbcTemplate jdbc) {
        this.tools = tools;
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String featureKey() {
        return AiProperties.STAFF_CHAT;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    @Override
    public boolean accepts(String role) {
        return "RECEPTIONIST".equals(role) || "INSTRUCTOR".equals(role);
    }

    @Override
    public boolean staff() {
        return true;
    }

    @Override
    public String context(Caller caller) {
        LocalDate today = LocalDate.now(StudentTools.VIETNAM);
        String home = caller.homeBranchId() == null ? null : jdbc.queryForList("SELECT name FROM yoga.branches WHERE id=:id",
                Map.of("id", caller.homeBranchId()), String.class).stream().findFirst().orElse(null);
        String who = "INSTRUCTOR".equals(caller.role()) ? "huấn luyện viên" : "lễ tân";
        return "[Ngữ cảnh: hôm nay là " + ManagerProfile.DAY.format(today) + " (" + today + "), giờ Việt Nam; người hỏi là " + who
                + (home == null ? "" : ", chi nhánh: " + home) + "]";
    }

    @Override
    public List<AiTool> tools(Caller caller) {
        return tools.tools(caller);
    }
}
