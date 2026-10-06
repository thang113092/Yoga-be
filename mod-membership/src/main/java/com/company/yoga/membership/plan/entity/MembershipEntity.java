package com.company.yoga.membership.plan.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "memberships", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
@DynamicUpdate
public class MembershipEntity extends BaseAuditEntity {

    @Column(name = "membership_code", nullable = false, unique = true, length = 50, updatable = false)
    private String membershipCode;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "registered_branch_id", nullable = false, updatable = false)
    private UUID registeredBranchId;

    @Column(name = "is_all_branches", nullable = false, updatable = false)
    private Boolean isAllBranches;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Column(name = "total_sessions", updatable = false)
    private Integer totalSessions;

    /**
     * Managed exclusively by Database Triggers & Ledger.
     * Application runtime CANNOT update this column directly.
     */
    @Column(name = "remaining_sessions", updatable = false)
    private Integer remainingSessions;

    /**
     * Managed by Payment Confirmation / Cancellation Triggers.
     */
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private String status = "PENDING_PAYMENT";

    @Column(name = "source_order_item_id", nullable = false, unique = true, updatable = false)
    private UUID sourceOrderItemId;

    @Column(name = "purchased_price", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal purchasedPrice;

    /**
     * Only notes can be updated directly by application runtime.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
