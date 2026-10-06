package com.company.yoga.schedule.booking.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "waitlists", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class WaitlistEntity extends BaseAuditEntity {

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    @Column(name = "queue_position", nullable = false)
    private Integer queuePosition;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "WAITING";

    @Column(name = "converted_booking_id")
    private UUID convertedBookingId;
}
