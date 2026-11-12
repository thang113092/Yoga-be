package com.company.yoga.membership.order.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.order.dto.PosOrderDto;
import com.company.yoga.membership.order.service.PosOrderService;
import com.company.yoga.membership.order.service.SelfServiceCheckoutService;
import com.company.yoga.membership.payment.online.OnlinePaymentDto;
import com.company.yoga.membership.payment.online.OnlinePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@Tag(name = "Student Online Checkout", description = "Học viên tự mua/gia hạn thẻ tập và thanh toán online")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@PreAuthorize("hasRole('STUDENT')")
public class SelfServiceCheckoutController {

    private final PosOrderService orders;
    private final OnlinePaymentService onlinePayments;
    private final SelfServiceCheckoutService checkout;

    @GetMapping("/checkout/quote")
    @Operation(summary = "Báo giá gói thẻ cho chính học viên (gồm khấu trừ khi đổi thẻ)")
    public ApiResponse<PosOrderDto.CheckoutQuote> quote(@RequestParam UUID planId, @RequestParam UUID branchId) {
        return ApiResponse.success(orders.getSelfServiceQuote(planId, branchId));
    }

    @PostMapping("/orders")
    @Operation(summary = "Học viên tạo đơn mua thẻ chờ thanh toán (Idempotency-Key bắt buộc)")
    public ApiResponse<PosOrderDto.OrderResp> createOrder(@Valid @RequestBody PosOrderDto.SelfServiceOrderReq req,
                                                          @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.success(orders.createSelfServiceOrder(req, key));
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "Học viên hủy đơn chưa thanh toán của mình")
    public ApiResponse<Void> cancelOrder(@PathVariable UUID orderId) {
        checkout.cancelOwnOrder(orderId);
        return ApiResponse.success(null);
    }

    @PostMapping("/orders/{orderId}/online-payments")
    @Operation(summary = "Tạo (hoặc lấy lại) mã VietQR cho đơn của học viên (Idempotency-Key bắt buộc)")
    public ApiResponse<OnlinePaymentDto.Resp> startOnlinePayment(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.success(onlinePayments.startForStudent(orderId, key));
    }

    @GetMapping("/online-payments/{id}")
    @Operation(summary = "Trạng thái mã thanh toán của học viên")
    public ApiResponse<OnlinePaymentDto.Resp> getOnlinePayment(@PathVariable UUID id) {
        return ApiResponse.success(onlinePayments.getForStudent(id));
    }

    @PostMapping("/online-payments/{id}/refresh")
    @Operation(summary = "Hỏi lại cổng thanh toán (khi quay về từ trang PayOS hoặc mã vừa hết hạn)")
    public ApiResponse<OnlinePaymentDto.Resp> refreshOnlinePayment(@PathVariable UUID id) {
        return ApiResponse.success(onlinePayments.refreshForStudent(id));
    }

    @PostMapping("/online-payments/{id}/cancel")
    @Operation(summary = "Học viên hủy mã thanh toán chưa trả")
    public ApiResponse<OnlinePaymentDto.Resp> cancelOnlinePayment(@PathVariable UUID id) {
        return ApiResponse.success(onlinePayments.cancelForStudent(id));
    }
}
