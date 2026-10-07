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

            String notes,
            UUID replacesMembershipId,
            BigDecimal expectedCredit,
            BigDecimal expectedTotal,
            BigDecimal adjustedCredit,
            String adjustmentReason
    ) {
        public CreateOrderReq(UUID branchId, UUID studentId, UUID planId, UUID cashierId, String notes) {
            this(branchId, studentId, planId, cashierId, notes, null, BigDecimal.ZERO, null, null, null);
        }
    }

    public record CheckoutQuote(com.company.yoga.membership.plan.dto.MembershipDto.Resp currentMembership,
            String currentPlanName, BigDecimal contractValue, BigDecimal credit, BigDecimal totalAmount, String issue) {}

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

    public record StudentOrderHistoryResp(
            UUID orderId,
            String orderCode,
            UUID branchId,
            String branchName,
            Instant orderDate,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            String status,
            String notes,
            java.util.List<OrderItemResp> items,
            java.util.List<PaymentResp> payments
    ) {}

    public record OrderItemResp(
            UUID itemId,
            String itemType,
            String itemName,
            BigDecimal unitPrice,
            Integer quantity,
            BigDecimal lineTotal,
            String membershipCode,
            String membershipStatus,
            Integer remainingSessions,
            Integer totalSessions,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate
    ) {}

    public record PaymentResp(
            UUID paymentId,
            String paymentCode,
            BigDecimal amount,
            String paymentMethod,
            String paymentStatus,
            Instant paymentTime
    ) {}
}
