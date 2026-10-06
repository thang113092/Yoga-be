package com.company.yoga.membership.revenue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.revenue.dao.RevenueReportDao;
import com.company.yoga.membership.revenue.dto.RevenueReportDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RevenueReportServiceTest {

    private AccessPolicy accessPolicy;
    private RevenueReportDao revenueReportDao;
    private BranchRepository branchRepository;
    private UserBranchRepository userBranchRepository;
    private RevenueReportService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final UUID branch1Id = UUID.randomUUID();
    private final UUID branch2Id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accessPolicy = mock(AccessPolicy.class);
        revenueReportDao = mock(RevenueReportDao.class);
        branchRepository = mock(BranchRepository.class);
        userBranchRepository = mock(UserBranchRepository.class);

        service = new RevenueReportService(
                accessPolicy,
                revenueReportDao,
                branchRepository,
                userBranchRepository
        );
    }

    @Test
    @DisplayName("Super Admin truy vấn báo cáo doanh thu toàn hệ thống thành công")
    void getRevenueReport_superAdmin_allBranches_success() {
        UserEntity admin = new UserEntity();
        admin.setId(adminId);
        when(accessPolicy.actor()).thenReturn(admin);
        when(accessPolicy.role(admin)).thenReturn("SUPER_ADMIN");

        RevenueReportDto.KpiOverview kpi = new RevenueReportDto.KpiOverview(
                BigDecimal.valueOf(50000000), 10, BigDecimal.valueOf(5000000), 10);
        when(revenueReportDao.getKpiOverview(any(), any(), eq(null), eq(null))).thenReturn(kpi);
        when(revenueReportDao.getDailyTrends(any(), any(), eq(null), eq(null))).thenReturn(List.of(
                new RevenueReportDto.DailyTrend("2026-10-01", BigDecimal.valueOf(20000000), 4)
        ));
        when(revenueReportDao.getPaymentMethodSummaries(any(), any(), eq(null), eq(null), any())).thenReturn(List.of());
        when(revenueReportDao.getPlanRevenueSummaries(any(), any(), eq(null), eq(null), any())).thenReturn(List.of());
        when(revenueReportDao.getBranchRevenueSummaries(any(), any(), eq(null), eq(null), any())).thenReturn(List.of());
        when(revenueReportDao.getRecentTransactions(any(), any(), eq(null), eq(null), eq(50))).thenReturn(List.of());

        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 5);

        RevenueReportDto.ReportResponse response = service.getRevenueReport(start, end, null, null);

        assertThat(response).isNotNull();
        assertThat(response.overview().totalRevenue()).isEqualByComparingTo("50000000");
        assertThat(response.overview().totalOrders()).isEqualTo(10);
        assertThat(response.dailyTrends()).hasSize(5); // 5 days filled from Oct 1 to Oct 5
    }

    @Test
    @DisplayName("Branch Manager truy vấn chi nhánh của mình thành công")
    void getRevenueReport_branchManager_ownBranch_success() {
        UserEntity manager = new UserEntity();
        manager.setId(managerId);
        manager.setHomeBranchId(branch1Id);
        when(accessPolicy.actor()).thenReturn(manager);
        when(accessPolicy.role(manager)).thenReturn("BRANCH_MANAGER");

        BranchEntity b1 = new BranchEntity();
        b1.setId(branch1Id);
        b1.setName("Cơ sở Quận 1");
        when(branchRepository.findById(branch1Id)).thenReturn(Optional.of(b1));

        RevenueReportDto.KpiOverview kpi = new RevenueReportDto.KpiOverview(
                BigDecimal.valueOf(15000000), 3, BigDecimal.valueOf(5000000), 3);
        when(revenueReportDao.getKpiOverview(any(), any(), eq(branch1Id), eq(null))).thenReturn(kpi);
        when(revenueReportDao.getDailyTrends(any(), any(), eq(branch1Id), eq(null))).thenReturn(List.of());
        when(revenueReportDao.getPaymentMethodSummaries(any(), any(), eq(branch1Id), eq(null), any())).thenReturn(List.of());
        when(revenueReportDao.getPlanRevenueSummaries(any(), any(), eq(branch1Id), eq(null), any())).thenReturn(List.of());
        when(revenueReportDao.getBranchRevenueSummaries(any(), any(), eq(branch1Id), eq(null), any())).thenReturn(List.of());
        when(revenueReportDao.getRecentTransactions(any(), any(), eq(branch1Id), eq(null), eq(50))).thenReturn(List.of());

        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 3);

        RevenueReportDto.ReportResponse response = service.getRevenueReport(start, end, branch1Id, null);

        assertThat(response).isNotNull();
        assertThat(response.filteredBranchId()).isEqualTo(branch1Id);
        assertThat(response.filteredBranchName()).isEqualTo("Cơ sở Quận 1");
    }

    @Test
    @DisplayName("Branch Manager cố tình truy vấn chi nhánh không được phân quyền sẽ bị chặn 403 Forbidden")
    void getRevenueReport_branchManager_unauthorizedBranch_throwsForbidden() {
        UserEntity manager = new UserEntity();
        manager.setId(managerId);
        manager.setHomeBranchId(branch1Id);
        when(accessPolicy.actor()).thenReturn(manager);
        when(accessPolicy.role(manager)).thenReturn("BRANCH_MANAGER");
        when(userBranchRepository.existsByUserIdAndBranchId(managerId, branch2Id)).thenReturn(false);

        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 3);

        assertThatThrownBy(() -> service.getRevenueReport(start, end, branch2Id, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
                });
    }
}
