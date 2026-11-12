package com.company.yoga.content.report;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Báo cáo online", description = "Lớp online, gói online, khóa học online và thư viện video")
@RestController
@RequiredArgsConstructor
public class OnlineReportController {

    private final OnlineReportService reports;

    @Operation(summary = "Báo cáo học online theo khoảng ngày (mặc định 30 ngày gần nhất)")
    @GetMapping("/api/v1/reports/online")
    public ApiResponse<OnlineReportService.Report> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) UUID branchId) {
        return ApiResponse.success(reports.report(startDate, endDate, branchId));
    }
}
