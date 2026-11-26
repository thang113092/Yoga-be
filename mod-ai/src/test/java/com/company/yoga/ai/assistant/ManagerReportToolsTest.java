package com.company.yoga.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.report.OnlineReportService;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.revenue.dto.RevenueReportDto;
import com.company.yoga.membership.revenue.service.RevenueReportService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ManagerReportToolsTest {

    private final RevenueReportService revenue = mock(RevenueReportService.class);
    private final AccessPolicy access = mock(AccessPolicy.class);
    private final ManagerReportTools tools = new ManagerReportTools(revenue, mock(OnlineReportService.class), access,
            mock(NamedParameterJdbcTemplate.class));

    private void signedInAs(String role, UUID home) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setHomeBranchId(home);
        when(access.actor()).thenReturn(user);
        when(access.role(user)).thenReturn(role);
    }

    private static RevenueReportDto.ReportResponse report(LocalDate from, LocalDate to, List<RevenueReportDto.DailyTrend> daily) {
        var tx = new RevenueReportDto.RecentTransaction(UUID.randomUUID(), "ORD1", UUID.randomUUID(), "PAY1", Instant.now(),
                UUID.randomUUID(), "Cầu Giấy", "Nguyễn Thị Hoa", "0912345678", "Thẻ 1 tháng", new BigDecimal("500000"), "CASH",
                "Tiền mặt", "Lễ tân An", "SUCCESS");
        return new RevenueReportDto.ReportResponse(
                new RevenueReportDto.KpiOverview(new BigDecimal("500000"), 1, new BigDecimal("500000"), 1), daily,
                List.of(new RevenueReportDto.PaymentMethodSummary("CASH", "Tiền mặt", new BigDecimal("500000"), 1, 100.0)),
                List.of(new RevenueReportDto.PlanRevenueSummary(UUID.randomUUID(), "Thẻ 1 tháng", 1, new BigDecimal("500000"), 100.0)),
                List.of(new RevenueReportDto.BranchRevenueSummary(UUID.randomUUID(), "CG", "Cầu Giấy", new BigDecimal("500000"), 1, 100.0)),
                List.of(tx), from, to, null, null);
    }

    @Test
    void revenueReportNeverPassesCustomerDetailsToTheModel() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        when(revenue.getRevenueReport(eq(from), eq(to), any(), any()))
                .thenReturn(report(from, to, List.of(new RevenueReportDto.DailyTrend("2026-10-05", new BigDecimal("500000"), 1))));

        Map<String, Object> out = tools.revenueReport().run(Map.of("startDate", "2026-10-01", "endDate", "2026-10-31"));

        String text = out.toString();
        assertThat(text).contains("totalRevenue=500000").contains("Cầu Giấy").contains("byDay")
                .doesNotContain("Nguyễn Thị Hoa").doesNotContain("0912345678").doesNotContain("Lễ tân An").doesNotContain("ORD1");
        assertThat(out.get("branch")).isEqualTo("Toàn chuỗi");
    }

    @Test
    void longPeriodsAreSummedByMonth() {
        var daily = new ArrayList<RevenueReportDto.DailyTrend>();
        daily.add(new RevenueReportDto.DailyTrend("2026-09-30", new BigDecimal("100"), 1));
        daily.add(new RevenueReportDto.DailyTrend("2026-10-01", new BigDecimal("200"), 2));
        daily.add(new RevenueReportDto.DailyTrend("2026-10-02", new BigDecimal("300"), 3));
        assertThat(ManagerReportTools.trend(daily, 90)).containsExactly(
                Map.of("month", "2026-09", "revenue", new BigDecimal("100"), "orders", 1L),
                Map.of("month", "2026-10", "revenue", new BigDecimal("500"), "orders", 5L));
        assertThat(ManagerReportTools.trend(daily, 3)).hasSize(3);
    }

    @Test
    void periodsAreValidatedAndCapped() {
        assertThat(ManagerReportTools.period(Map.of("startDate", "2026-10-31", "endDate", "2026-10-01")).start())
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThatThrownBy(() -> ManagerReportTools.period(Map.of("startDate", "01/10/2026", "endDate", "2026-10-31")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> ManagerReportTools.period(Map.of("startDate", "2024-01-01", "endDate", "2026-10-31")))
                .hasMessageContaining("366");
        assertThatThrownBy(() -> ManagerReportTools.period(Map.of("endDate", "2026-10-31"))).hasMessageContaining("startDate");
        assertThatThrownBy(() -> ManagerReportTools.uuid("Cầu Giấy")).hasMessageContaining("list_branches");
    }

    @Test
    void branchManagerIsHeldToTheirBranches() {
        UUID home = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        signedInAs("BRANCH_MANAGER", home);
        assertThat(tools.branchScope(null)).isEqualTo(home);
        tools.branchScope(other);
        verify(access).requireStaffBranch(other);
    }

    @Test
    void superAdminSeesTheWholeChain() {
        signedInAs("SUPER_ADMIN", null);
        assertThat(tools.branchScope(null)).isNull();
        assertThat(tools.scope().superAdmin()).isTrue();
    }

    @Test
    void otherRolesCannotUseTheAssistant() {
        signedInAs("RECEPTIONIST", UUID.randomUUID());
        assertThatThrownBy(tools::scope).isInstanceOf(BusinessException.class);
        signedInAs("STUDENT", null);
        assertThatThrownBy(() -> tools.branchScope(null)).isInstanceOf(BusinessException.class);
    }
}
