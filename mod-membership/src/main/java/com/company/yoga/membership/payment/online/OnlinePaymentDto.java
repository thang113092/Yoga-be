package com.company.yoga.membership.payment.online;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class OnlinePaymentDto {

    private OnlinePaymentDto() {}

    public record Resp(
            UUID id,
            UUID paymentId,
            UUID orderId,
            String provider,
            Long providerOrderCode,
            String status,
            BigDecimal amount,
            String checkoutUrl,
            String qrPayload,
            Instant expiresAt,
            Instant paidAt,
            String paymentStatus,
            String orderStatus,
            String activatedMembershipCode
    ) {}

    /** Money received at the gateway whose order could not be activated. */
    public record ReconciliationRow(
            UUID id,
            UUID orderId,
            String orderCode,
            UUID branchId,
            String branchName,
            String customerName,
            String customerPhone,
            long providerOrderCode,
            BigDecimal amount,
            BigDecimal paidAmount,
            Instant paidAt,
            String providerReference,
            String orderStatus,
            Instant createdAt
    ) {}

    public record ResolveReq(String note) {}
}
