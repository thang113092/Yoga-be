package com.company.yoga.membership.revenue.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RevenueReportDto {

    private RevenueReportDto() {}

    @Schema(description = "Yêu cầu lọc báo cáo doanh thu")
    public record FilterReq(
            @Schema(description = "Ngày bắt đầu (mặc định 30 ngày trước)")
            LocalDate startDate,

            @Schema(description = "Ngày kết thúc (mặc định hôm nay)")
            LocalDate endDate,

            @Schema(description = "Mã chi nhánh (null = tất cả chi nhánh nếu là Super Admin)")
            UUID branchId,

            @Schema(description = "Phương thức thanh toán: CASH, POS_CARD, BANK_TRANSFER_QR")
            String paymentMethod
    ) {}

    @Schema(description = "Tổng quan chỉ số KPI doanh thu")
    public record KpiOverview(
            @Schema(description = "Tổng doanh thu thực thu (VNĐ)")
            BigDecimal totalRevenue,

            @Schema(description = "Tổng số giao dịch / đơn hàng hoàn thành")
            long totalOrders,

            @Schema(description = "Giá trị trung bình mỗi đơn (AOV)")
            BigDecimal averageOrderValue,

            @Schema(description = "Tổng số thẻ hội viên đã kích hoạt")
            long totalMembershipsActivated
    ) {}

    @Schema(description = "Biến động doanh thu theo ngày")
    public record DailyTrend(
            @Schema(description = "Ngày theo định dạng YYYY-MM-DD")
            String date,

            @Schema(description = "Doanh thu trong ngày")
            BigDecimal amount,

            @Schema(description = "Số đơn trong ngày")
            long orderCount
    ) {}

    @Schema(description = "Cơ cấu doanh thu theo phương thức thanh toán")
    public record PaymentMethodSummary(
            @Schema(description = "Mã phương thức: CASH, POS_CARD, BANK_TRANSFER_QR")
            String paymentMethod,

            @Schema(description = "Tên hiển thị phương thức thanh toán")
            String methodName,

            @Schema(description = "Tổng tiền")
            BigDecimal totalAmount,

            @Schema(description = "Số giao dịch")
            long transactionCount,

            @Schema(description = "Tỷ lệ phần trăm doanh thu (%)")
            double percentage
    ) {}

    @Schema(description = "Top gói thẻ tập đóng góp doanh thu")
    public record PlanRevenueSummary(
            @Schema(description = "Mã gói tập")
            UUID planId,

            @Schema(description = "Tên gói tập")
            String planName,

            @Schema(description = "Số lượng bán ra")
            long quantitySold,

            @Schema(description = "Tổng doanh thu từ gói")
            BigDecimal totalRevenue,

            @Schema(description = "Tỷ lệ phần trăm (%)")
            double percentage
    ) {}

    @Schema(description = "Doanh thu theo từng cơ sở / chi nhánh")
    public record BranchRevenueSummary(
            @Schema(description = "ID chi nhánh")
            UUID branchId,

            @Schema(description = "Mã chi nhánh")
            String branchCode,

            @Schema(description = "Tên chi nhánh")
            String branchName,

            @Schema(description = "Tổng doanh thu cơ sở")
            BigDecimal totalRevenue,

            @Schema(description = "Số lượng đơn hàng cơ sở")
            long orderCount,

            @Schema(description = "Tỷ lệ phần trăm chuỗi (%)")
            double percentage
    ) {}

    @Schema(description = "Giao dịch / đơn thanh toán gần đây")
    public record RecentTransaction(
            UUID orderId,
            String orderCode,
            UUID paymentId,
            String paymentCode,
            Instant paymentTime,
            UUID branchId,
            String branchName,
            String customerName,
            String customerPhone,
            String planName,
            BigDecimal amount,
            String paymentMethod,
            String paymentMethodName,
            String cashierName,
            String status
    ) {}

    @Schema(description = "Báo cáo tổng hợp doanh thu chi tiết")
    public record ReportResponse(
            KpiOverview overview,
            List<DailyTrend> dailyTrends,
            List<PaymentMethodSummary> paymentMethodSummaries,
            List<PlanRevenueSummary> planSummaries,
            List<BranchRevenueSummary> branchSummaries,
            List<RecentTransaction> recentTransactions,
            LocalDate fromDate,
            LocalDate toDate,
            UUID filteredBranchId,
            String filteredBranchName
    ) {}
}
