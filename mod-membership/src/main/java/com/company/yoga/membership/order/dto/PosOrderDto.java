package com.company.yoga.membership.order.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PosOrderDto {

    private PosOrderDto() {}

    public record CreateOrderReq(
            @NotNull(message = "Chi nhánh không được để trống")
            UUID branchId,

            @NotNull(message = "Học viên không được để trống")
            UUID studentId,

            @NotNull(message = "Gói tập không được để trống")
            UUID planId,

            @NotNull(message = "Thu ngân không được để trống")
            UUID cashierId,

            String notes
    ) {}

    public record OrderResp(
            UUID orderId,
            String orderCode,
            UUID branchId,
            UUID studentId,
            UUID planId,
            String planName,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            String status,
            Instant orderDate,
            UUID membershipId,
            String membershipCode
    ) {}
}
