package com.company.yoga.schedule.payroll;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Thù lao HLV", description = "Chính sách đơn giá (tại phòng/online) và bảng lương xem trước theo tháng")
@RestController
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payroll;

    @GetMapping("/api/v1/payroll/rate-policies")
    public ApiResponse<List<PayrollService.Policy>> policies(@RequestParam(required = false) UUID instructorId) {
        return ApiResponse.success(payroll.policies(instructorId));
    }

    @Operation(summary = "Tạo đơn giá mới (đóng đơn giá đang áp dụng cùng phạm vi từ ngày hiệu lực)")
    @PostMapping("/api/v1/payroll/rate-policies")
    public ApiResponse<PayrollService.Policy> create(@Valid @RequestBody PayrollService.SavePolicy req) {
        return ApiResponse.success(payroll.createPolicy(req));
    }

    @PostMapping("/api/v1/payroll/rate-policies/{id}/deactivate")
    public ApiResponse<Void> deactivate(@PathVariable UUID id) {
        payroll.deactivate(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Bảng lương xem trước: buổi đã dạy trong tháng theo hình thức, số học viên, tiền theo đơn giá")
    @GetMapping("/api/v1/payroll/preview")
    public ApiResponse<PayrollService.Preview> preview(@RequestParam int year, @RequestParam int month,
                                                      @RequestParam(required = false) UUID branchId,
                                                      @RequestParam(required = false) UUID instructorId) {
        return ApiResponse.success(payroll.preview(year, month, branchId, instructorId));
    }
}
