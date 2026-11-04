package com.company.yoga.membership.payment.online;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "POS Online Payments", description = "VietQR động tại quầy qua cổng thanh toán")
@RestController
@RequestMapping("/api/v1/pos")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
public class OnlinePaymentController {

    private final OnlinePaymentService onlinePayments;
    private final OnlinePaymentReconciliationService reconciliations;

    @PostMapping("/orders/{orderId}/online-payments")
    @Operation(summary = "Tạo mã VietQR động cho đơn chờ thanh toán (Idempotency-Key bắt buộc)")
    public ApiResponse<OnlinePaymentDto.Resp> start(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.success(onlinePayments.startAtCounter(orderId, key));
    }

    @GetMapping("/online-payments/{id}")
    @Operation(summary = "Trạng thái mã thanh toán online")
    public ApiResponse<OnlinePaymentDto.Resp> get(@PathVariable UUID id) {
        return ApiResponse.success(onlinePayments.get(id));
    }

    @PostMapping("/online-payments/{id}/refresh")
    @Operation(summary = "Hỏi lại cổng thanh toán khi chưa nhận được thông báo")
    public ApiResponse<OnlinePaymentDto.Resp> refresh(@PathVariable UUID id) {
        return ApiResponse.success(onlinePayments.refresh(id));
    }

    @PostMapping("/online-payments/{id}/cancel")
    @Operation(summary = "Hủy mã thanh toán chưa được trả")
    public ApiResponse<OnlinePaymentDto.Resp> cancel(@PathVariable UUID id) {
        return ApiResponse.success(onlinePayments.cancel(id));
    }

    @GetMapping("/online-payments/reconciliation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Tiền đã về cổng nhưng chưa kích hoạt được đơn, chờ quản lý đối soát")
    public ApiResponse<java.util.List<OnlinePaymentDto.ReconciliationRow>> reconciliation(@RequestParam(required = false) UUID branchId) {
        return ApiResponse.success(reconciliations.pending(branchId));
    }

    @PostMapping("/online-payments/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Quản lý ghi nhận đã xử lý khoản tiền chờ đối soát")
    public ApiResponse<Void> resolve(@PathVariable UUID id, @RequestBody OnlinePaymentDto.ResolveReq req) {
        reconciliations.resolve(id, req == null ? null : req.note());
        return ApiResponse.success(null);
    }
}
