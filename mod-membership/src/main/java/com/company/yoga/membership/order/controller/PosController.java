package com.company.yoga.membership.order.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.order.dto.PaymentDto;
import com.company.yoga.membership.order.dto.PosOrderDto;
import com.company.yoga.membership.order.service.PaymentService;
import com.company.yoga.membership.order.service.PosOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "POS & Orders", description = "Quầy bán thẻ POS, lập đơn hàng và thu tiền kích hoạt thẻ")
@RestController
@RequestMapping("/api/v1/pos")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
public class PosController {

    private final PosOrderService posOrderService;
    private final PaymentService paymentService;
    private final com.company.yoga.membership.order.service.OrderListService orderListService;

    @org.springframework.web.bind.annotation.GetMapping("/orders")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Danh sách đơn hàng theo quyền quản lý cơ sở")
    public ApiResponse<com.company.yoga.membership.order.service.OrderListService.Result> listOrders(
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.UUID branchId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String search,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(orderListService.list(branchId, status, search, page, size));
    }

    @PostMapping("/orders")
    @Operation(summary = "Tạo đơn hàng mua thẻ tập tại quầy")
    public ApiResponse<PosOrderDto.OrderResp> createOrder(@Valid @RequestBody PosOrderDto.CreateOrderReq req, @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") java.util.UUID key) {
        return ApiResponse.success(posOrderService.createMembershipOrder(req, key));
    }

    @PostMapping("/payments")
    @Operation(summary = "Thu tiền và kích hoạt thẻ tập (Có Idempotency-Key chống ghi đúp)")
    public ApiResponse<PaymentDto.Resp> processPayment(@Valid @RequestBody PaymentDto.PayReq req) {
        return ApiResponse.success(paymentService.processPayment(req));
    }

    @org.springframework.web.bind.annotation.GetMapping("/orders/student/{studentId}")
    @Operation(summary = "Lấy lịch sử mua thẻ của học viên")
    public ApiResponse<java.util.List<PosOrderDto.StudentOrderHistoryResp>> getStudentOrders(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID studentId
    ) {
        return ApiResponse.success(posOrderService.getStudentOrders(studentId));
    }
}
