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

    /** Chosen at creation and fixed afterwards (V13): VIRTUAL rooms are streaming channels (Zoom licences). */
    @Column(name = "room_type", nullable = false, updatable = false, length = 20)
    private String roomType = "PHYSICAL";

    /** Zoom host (email or user id) for a virtual room; ignored for studio rooms. */
    @Column(name = "live_host_account", length = 150)
    private String liveHostAccount;
}
