package com.company.yoga.membership.order.entity;

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

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "payments", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class PaymentEntity extends BaseCreationAuditEntity {

    @Column(name = "payment_code", nullable = false, unique = true, length = 50)
    private String paymentCode;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus = "PENDING";

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "payment_time", nullable = false)
    private Instant paymentTime = Instant.now();

    @Column(name = "receipt_url", columnDefinition = "TEXT")
    private String receiptUrl;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
