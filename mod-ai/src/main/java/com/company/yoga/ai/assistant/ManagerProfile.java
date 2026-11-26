package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProperties;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Report assistant for chain owners and branch managers (phase 2). */
@Component
public class ManagerProfile implements AssistantProfile {

    static final String KIND = "MANAGER_ASSISTANT";
    static final String PROMPT = "manager-assistant";
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy", Locale.forLanguageTag("vi"));

    private final ManagerReportTools reports;

    public ManagerProfile(ManagerReportTools reports) {
        this.reports = reports;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String featureKey() {
        return AiProperties.MANAGER_CHAT;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    @Override
    public boolean accepts(String role) {
        return "SUPER_ADMIN".equals(role) || "BRANCH_MANAGER".equals(role);
    }

    @Override
    public boolean staff() {
        return true;
    }

    @Override
    public String context(Caller caller) {
        LocalDate today = LocalDate.now(ManagerReportTools.VIETNAM);
        return "[Ngữ cảnh: hôm nay là " + DAY.format(today) + " (" + today + "), giờ Việt Nam; người hỏi là "
                + ("SUPER_ADMIN".equals(caller.role()) ? "chủ chuỗi, xem được mọi chi nhánh" : "quản lý chi nhánh, chỉ xem được chi nhánh của mình") + "]";
    }

    @Override
    public List<AiTool> tools() {
        return reports.tools();
    }
}
