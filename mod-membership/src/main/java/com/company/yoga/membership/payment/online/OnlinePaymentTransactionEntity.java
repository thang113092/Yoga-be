package com.company.yoga.membership.payment.online;

import com.company.yoga.common.entity.BaseCreationAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** One payment link at a gateway. Runtime may only update the columns granted in V10. */
@Entity
@DynamicUpdate
@Table(name = "online_payment_transactions", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class OnlinePaymentTransactionEntity extends BaseCreationAuditEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private String provider;

    @Generated(event = EventType.INSERT)
    @Column(name = "provider_order_code", insertable = false, updatable = false)
    private Long providerOrderCode;

    @Column(name = "provider_link_id", length = 100)
    private String providerLinkId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "qr_payload", columnDefinition = "TEXT")
    private String qrPayload;

    @Column(name = "amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "CREATED";

    @Column(name = "paid_amount", precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Generated(event = EventType.UPDATE)
    @Column(name = "resolved_at", insertable = false, updatable = false)
    private Instant resolvedAt;
}
