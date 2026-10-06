package com.company.yoga.membership.plan.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "membership_plans", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class MembershipPlanEntity extends BaseAuditEntity {

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "plan_type", nullable = false, length = 20)
    private String planType;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "total_sessions")
    private Integer totalSessions;

    @Column(name = "is_all_branches", nullable = false)
    private Boolean isAllBranches = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
