package com.company.yoga.membership.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDto {

    private PaymentDto() {}

    public record PayReq(
            @NotNull(message = "Mã đơn hàng không được để trống")
            UUID orderId,

            @NotBlank(message = "Phương thức thanh toán không được để trống")
            String paymentMethod,

            @NotNull(message = "Idempotency-Key bắt buộc phải có để chống ghi đúp")
            UUID idempotencyKey,

            @NotNull(message = "Thu ngân xác nhận không được để trống")
            UUID cashierId,

            String transactionReference,
            String notes
    ) {}

    public record Resp(
            UUID paymentId,
            String paymentCode,
            UUID orderId,
            BigDecimal amount,
            String paymentMethod,
            String paymentStatus,
            Instant paymentTime,
            String activatedMembershipCode
    ) {}
}
