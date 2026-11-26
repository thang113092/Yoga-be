package com.company.yoga.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.ai.AiProperties;
import com.company.yoga.ai.AiProvider;
import com.company.yoga.ai.AiProvider.Role;
import com.company.yoga.ai.AiProvider.ToolCall;
import com.company.yoga.ai.AiProvider.Turn;
import com.company.yoga.ai.AiResultCodes;
import com.company.yoga.ai.AiUsageService;
import com.company.yoga.ai.Prompts;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.common.api.CommonErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ManagerAssistantServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final AiProperties.Feature FEATURE = new AiProperties.Feature("chat-model", 2048, null, null,
            new BigDecimal("0.3"), null, new BigDecimal("2.5"), 2);

    private final AiProvider provider = mock(AiProvider.class);
    private final AiUsageService usage = mock(AiUsageService.class);
    private final ManagerReportTools reports = mock(ManagerReportTools.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final List<Map<String, Object>> toolArgs = new ArrayList<>();
    private ManagerAssistantService service;

    private AiTool revenueTool = new AiTool() {
        public AiProvider.ToolSpec spec() {
            return new AiProvider.ToolSpec("get_revenue_report", "Doanh thu", Map.of("type", "object"));
        }

        public Map<String, Object> run(Map<String, Object> args) {
            toolArgs.add(args);
            return Map.of("totalRevenue", 1_000_000);
        }
    };

    private static AiProperties props(boolean enabled) {
        return new AiProperties(enabled, "gemini", new AiProperties.Gemini("k", "u", 60, 0),
                Map.of(AiProperties.MANAGER_CHAT, FEATURE), new AiProperties.Quota(30, 100, BigDecimal.TEN, 80),
                new AiProperties.Conversations(30, 60));
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(provider.isConfigured()).thenReturn(true);
        when(provider.name()).thenReturn("gemini");
        when(reports.scope()).thenReturn(new ManagerReportTools.Scope(ME, null, true));
        when(reports.tools()).thenAnswer(i -> List.of(revenueTool));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(String.class))).thenReturn(List.of("Hội thoại mới"));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        org.mockito.Mockito.doAnswer(i -> {
            ((Consumer<TransactionStatus>) i.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new ManagerAssistantService(props(true), provider, usage, new Prompts(), reports, jdbc, tx, new ObjectMapper());
    }

    private static AiProvider.ChatResult answer(String text) {
        return new AiProvider.ChatResult(new Turn(Role.MODEL, text, List.of(), List.of(), null), "STOP", new AiProvider.Usage(100, 0, 20));
    }

    private static AiProvider.ChatResult call(String name, Map<String, Object> args) {
        return new AiProvider.ChatResult(new Turn(Role.MODEL, "", List.of(new ToolCall("c1", name, args)), List.of(), null), "STOP",
                new AiProvider.Usage(80, 0, 10));
    }

    private int inserts() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeast(0)).update(sql.capture(), any(SqlParameterSource.class));
        return (int) sql.getAllValues().stream().filter(s -> s.contains("INSERT INTO yoga.ai_messages")).count();
    }

    @Test
    void runsToolsThenAnswersAndStoresAllTurnsTogether() {
        var requests = ArgumentCaptor.forClass(AiProvider.ChatRequest.class);
        when(provider.chat(requests.capture()))
                .thenReturn(call("get_revenue_report", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31")))
                .thenReturn(answer("Doanh thu tháng 10 là **1.000.000 đ**."));

        var a = service.ask(CONVERSATION, "  Doanh thu tháng 10 bao nhiêu?  ");

        assertThat(a.message().text()).isEqualTo("Doanh thu tháng 10 là **1.000.000 đ**.");
        assertThat(a.message().toolsUsed()).containsExactly("get_revenue_report");
        assertThat(a.title()).isEqualTo("Doanh thu tháng 10 bao nhiêu?");
        assertThat(toolArgs).containsExactly(Map.of("startDate", "2026-10-01", "endDate", "2026-10-31"));

        List<Turn> second = requests.getAllValues().get(1).history();
        assertThat(second).extracting(Turn::role).containsExactly(Role.USER, Role.MODEL, Role.TOOL);
        assertThat(second.get(0).text()).startsWith("[Ngữ cảnh: hôm nay là").endsWith("Doanh thu tháng 10 bao nhiêu?");
        assertThat(second.get(2).results().get(0).response()).containsEntry("totalRevenue", 1_000_000);

        verify(usage).checkQuota(ME, true);
        verify(usage).record(eq(ME), eq(FEATURE), any(), eq(true));
        verify(usage).record(eq(ME), eq(FEATURE), any(), eq(false));
        assertThat(inserts()).isEqualTo(4);
    }

    @Test
    void lastRoundForbidsToolsAndDropsUnansweredCalls() {
        var requests = ArgumentCaptor.forClass(AiProvider.ChatRequest.class);
        when(provider.chat(requests.capture()))
                .thenReturn(call("get_revenue_report", Map.of()))
                .thenReturn(call("get_revenue_report", Map.of()))
                .thenReturn(new AiProvider.ChatResult(new Turn(Role.MODEL, "Tôi cần thêm thông tin.",
                        List.of(new ToolCall(null, "get_revenue_report", Map.of())), List.of(), null), "STOP", AiProvider.Usage.NONE));

        var a = service.ask(CONVERSATION, "Doanh thu?");

        assertThat(requests.getAllValues()).extracting(AiProvider.ChatRequest::allowTools).containsExactly(true, true, false);
        assertThat(a.message().text()).isEqualTo("Tôi cần thêm thông tin.");
        verify(jdbc).update(anyString(), argThat((SqlParameterSource p) -> p instanceof MapSqlParameterSource m
                && Integer.valueOf(6).equals(m.getValues().get("seq")) && m.getValue("content").toString().contains("\"calls\":[]")));
    }

    @Test
    void refusedToolGoesBackToTheModelAsAnError() {
        revenueTool = new AiTool() {
            public AiProvider.ToolSpec spec() {
                return new AiProvider.ToolSpec("get_revenue_report", "Doanh thu", Map.of("type", "object"));
            }

            public Map<String, Object> run(Map<String, Object> args) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN, "Bạn không có quyền xem báo cáo của chi nhánh này.");
            }
        };
        var requests = ArgumentCaptor.forClass(AiProvider.ChatRequest.class);
        when(provider.chat(requests.capture())).thenReturn(call("get_revenue_report", Map.of())).thenReturn(answer("Bạn không có quyền."));
        service.ask(CONVERSATION, "Doanh thu chi nhánh khác?");
        assertThat(requests.getAllValues().get(1).history().get(2).results().get(0).response())
                .containsEntry("error", "Bạn không có quyền xem báo cáo của chi nhánh này.");
    }

    @Test
    void unknownToolIsReportedNotRun() {
        assertThat(service.runTool(null, new ToolCall(null, "drop_tables", Map.of()))).containsEntry("error", "Không có hàm drop_tables");
    }

    @Test
    void failedQuestionStoresNothing() {
        when(provider.chat(any())).thenReturn(call("get_revenue_report", Map.of()))
                .thenThrow(new AiProvider.ProviderException(AiProvider.Failure.RATE_LIMITED, "429", null));
        assertThatThrownBy(() -> service.ask(CONVERSATION, "Doanh thu?")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AiResultCodes.BUSY));
        assertThat(inserts()).isZero();
        verify(tx, never()).executeWithoutResult(any());
    }

    @Test
    void someoneElsesConversationIsNotFound() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(String.class))).thenReturn(List.of());
        assertThatThrownBy(() -> service.ask(CONVERSATION, "Doanh thu?")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AiResultCodes.CONVERSATION_NOT_FOUND));
        verify(provider, never()).chat(any());
    }

    @Test
    void offAndEmptyQuestionsAreRefusedBeforeAnyCall() {
        var off = new ManagerAssistantService(props(false), provider, usage, new Prompts(), reports, jdbc, tx, new ObjectMapper());
        assertThatThrownBy(() -> off.ask(CONVERSATION, "Doanh thu?")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AiResultCodes.DISABLED));
        assertThatThrownBy(() -> service.ask(CONVERSATION, "   ")).isInstanceOf(BusinessException.class);
        verify(provider, never()).chat(any());
        verify(usage, never()).checkQuota(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void longQuestionsMakeShortTitles() {
        assertThat(ManagerAssistantService.title("So sánh   doanh thu\n" + "x".repeat(100))).hasSize(60).endsWith("…");
        assertThat(ManagerAssistantService.context(new ManagerReportTools.Scope(ME, null, false))).contains("quản lý chi nhánh");
    }

    @Test
    void promptFileLoads() {
        assertThat(new Prompts().get(ManagerAssistantService.PROMPT).version()).isEqualTo("manager-assistant/1");
        verify(provider, times(0)).chat(any());
    }
}
