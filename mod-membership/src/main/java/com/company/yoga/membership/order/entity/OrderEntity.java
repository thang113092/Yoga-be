package com.company.yoga.membership.order.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
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
@Table(name = "orders", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class OrderEntity extends BaseAuditEntity {

    @Column(name = "order_code", nullable = false, unique = true, length = 50)
    private String orderCode;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "order_date", nullable = false)
    private Instant orderDate = Instant.now();

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private String status = "PENDING";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
