package com.company.yoga.branch.facility.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "rooms", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class RoomEntity extends BaseAuditEntity {

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "floor", length = 50)
    private String floor;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
