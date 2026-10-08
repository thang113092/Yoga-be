package com.company.yoga.membership.order.entity;

import com.company.yoga.common.entity.BaseCreationAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "order_items", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class OrderItemEntity extends BaseCreationAuditEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "item_type", nullable = false, length = 30)
    private String itemType = "MEMBERSHIP_PLAN";

    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "course_class_id")
    private UUID courseClassId;

    @Column(name = "item_name_snapshot", nullable = false)
    private String itemNameSnapshot;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;
}
