package com.company.yoga.schedule.booking.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "class_schedules", schema = "yoga")
@jakarta.persistence.AttributeOverride(name = "createdAt", column = @Column(name = "created_at", insertable = false, updatable = false))
@Getter
@Setter
@NoArgsConstructor
public class ClassScheduleEntity extends BaseAuditEntity {

    @Column(name = "course_class_id")
    private UUID courseClassId;
    @Column(name = "session_number")
    private Integer sessionNumber;
    @Column(name = "session_title")
    private String sessionTitle;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "class_type_id", nullable = false)
    private UUID classTypeId;

    @Column(name = "instructor_id", nullable = false)
    private UUID instructorId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    /**
     * Managed by Trigger maintain_booking_balances.
     * Application runtime CANNOT update this column directly.
     */
    @Column(name = "booked_count", nullable = false, insertable = false, updatable = false)
    private Integer bookedCount;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "SCHEDULED";

    @Column(name = "version", nullable = false, insertable = false, updatable = false)
    private Long version;
}
