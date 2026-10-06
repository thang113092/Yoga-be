package com.company.yoga.membership.revenue.service;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RevenueReportService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AccessPolicy accessPolicy;
    private final RevenueReportDao revenueReportDao;
    private final BranchRepository branchRepository;
    private final UserBranchRepository userBranchRepository;

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public RevenueReportDto.ReportResponse getRevenueReport(
            LocalDate startDate,
            LocalDate endDate,
            UUID branchId,
            String paymentMethod
    ) {
        UserEntity actor = accessPolicy.actor();
        String role = accessPolicy.role(actor);

        // 1. Phân quyền chi nhánh (RBAC)
        UUID effectiveBranchId = branchId;
        if ("BRANCH_MANAGER".equalsIgnoreCase(role)) {
            if (branchId != null) {
                boolean isPrimary = Objects.equals(actor.getHomeBranchId(), branchId);
                boolean isAssigned = userBranchRepository.existsByUserIdAndBranchId(actor.getId(), branchId);
                if (!isPrimary && !isAssigned) {
                    log.warn("Branch Manager {} attempted to access unauthorized branch {}", actor.getId(), branchId);
                    throw new BusinessException(CommonErrorCode.FORBIDDEN, "Bạn không có quyền xem báo cáo của chi nhánh này.");
                }
            } else {
                effectiveBranchId = actor.getHomeBranchId();
                if (effectiveBranchId == null) {
                    List<UUID> assigned = userBranchRepository.branchIdsForUser(actor.getId());
                    if (!assigned.isEmpty()) {
                        effectiveBranchId = assigned.get(0);
                    }
                }
            }
        }

        // 2. Chuẩn hóa khoảng thời gian (SARGable)
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        LocalDate effectiveEndDate = endDate != null ? endDate : today;
        LocalDate effectiveStartDate = startDate != null ? startDate : effectiveEndDate.minusDays(29);

        if (effectiveStartDate.isAfter(effectiveEndDate)) {
            LocalDate temp = effectiveStartDate;
            effectiveStartDate = effectiveEndDate;
            effectiveEndDate = temp;
        }

        Instant fromInstant = effectiveStartDate.atStartOfDay(VIETNAM_ZONE).toInstant();
        Instant toInstant = effectiveEndDate.plusDays(1).atStartOfDay(VIETNAM_ZONE).toInstant();

        String normalizedPaymentMethod = (paymentMethod != null && !paymentMethod.isBlank() && !"ALL".equalsIgnoreCase(paymentMethod.trim()))
                ? paymentMethod.trim().toUpperCase()
                : null;

        // 3. Thực hiện truy vấn tổng hợp
        RevenueReportDto.KpiOverview overview = revenueReportDao.getKpiOverview(
                fromInstant, toInstant, effectiveBranchId, normalizedPaymentMethod);

        List<RevenueReportDto.DailyTrend> rawTrends = revenueReportDao.getDailyTrends(
                fromInstant, toInstant, effectiveBranchId, normalizedPaymentMethod);

        // Bổ sung các ngày còn trống trong khoảng lọc để biểu đồ mượt mà liên tục
        List<RevenueReportDto.DailyTrend> dailyTrends = fillMissingDates(effectiveStartDate, effectiveEndDate, rawTrends);

        List<RevenueReportDto.PaymentMethodSummary> paymentMethodSummaries = revenueReportDao.getPaymentMethodSummaries(
                fromInstant, toInstant, effectiveBranchId, normalizedPaymentMethod, overview.totalRevenue());

        List<RevenueReportDto.PlanRevenueSummary> planSummaries = revenueReportDao.getPlanRevenueSummaries(
                fromInstant, toInstant, effectiveBranchId, normalizedPaymentMethod, overview.totalRevenue());

        List<RevenueReportDto.BranchRevenueSummary> branchSummaries = revenueReportDao.getBranchRevenueSummaries(
                fromInstant, toInstant, effectiveBranchId, normalizedPaymentMethod, overview.totalRevenue());

        List<RevenueReportDto.RecentTransaction> recentTransactions = revenueReportDao.getRecentTransactions(
                fromInstant, toInstant, effectiveBranchId, normalizedPaymentMethod, 50);

        String filteredBranchName = null;
        if (effectiveBranchId != null) {
            filteredBranchName = branchRepository.findById(effectiveBranchId)
                    .map(BranchEntity::getName)
                    .orElse(null);
        }

        return new RevenueReportDto.ReportResponse(
                overview,
                dailyTrends,
                paymentMethodSummaries,
                planSummaries,
                branchSummaries,
                recentTransactions,
                effectiveStartDate,
                effectiveEndDate,
                effectiveBranchId,
                filteredBranchName
        );
    }

    private List<RevenueReportDto.DailyTrend> fillMissingDates(
            LocalDate start,
            LocalDate end,
            List<RevenueReportDto.DailyTrend> rawList
    ) {
        Map<String, RevenueReportDto.DailyTrend> map = new HashMap<>();
        for (RevenueReportDto.DailyTrend trend : rawList) {
            map.put(trend.date(), trend);
        }

        List<RevenueReportDto.DailyTrend> fullList = new ArrayList<>();
        LocalDate curr = start;
        while (!curr.isAfter(end)) {
            String dateStr = curr.format(DATE_FORMATTER);
            RevenueReportDto.DailyTrend existing = map.get(dateStr);
            if (existing != null) {
                fullList.add(existing);
            } else {
                fullList.add(new RevenueReportDto.DailyTrend(dateStr, BigDecimal.ZERO, 0L));
            }
            curr = curr.plusDays(1);
        }

        return fullList;
    }
}
