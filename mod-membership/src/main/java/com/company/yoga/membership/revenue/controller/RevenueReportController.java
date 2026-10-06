package com.company.yoga.membership.revenue.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.revenue.dto.RevenueReportDto;
import com.company.yoga.membership.revenue.service.RevenueReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Revenue Analytics & Reports", description = "Tổng hợp, thống kê và báo cáo doanh thu tài chính (Super Admin & Branch Manager)")
@RestController
@RequestMapping("/api/v1/revenue")
@RequiredArgsConstructor
public class RevenueReportController {

    private final RevenueReportService revenueReportService;

    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Lấy báo cáo tổng hợp doanh thu theo kỳ, cơ sở và phương thức thanh toán")
    public ApiResponse<RevenueReportDto.ReportResponse> getRevenueReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String paymentMethod
    ) {
        RevenueReportDto.ReportResponse report = revenueReportService.getRevenueReport(
                startDate, endDate, branchId, paymentMethod);
        return ApiResponse.success(report);
    }
}
