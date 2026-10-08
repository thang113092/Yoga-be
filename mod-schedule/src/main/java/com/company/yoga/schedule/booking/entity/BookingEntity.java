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
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "bookings", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
@DynamicUpdate
public class BookingEntity extends BaseAuditEntity {

    @Column(name = "booking_code", nullable = false, unique = true, length = 50, updatable = false)
    private String bookingCode;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "membership_id", updatable = false)
    private UUID membershipId;

    @Column(name = "course_enrollment_id", updatable = false)
    private UUID courseEnrollmentId;

    @Column(name = "booking_time", nullable = false, updatable = false)
    private Instant bookingTime = Instant.now();

    @Column(name = "status", nullable = false, length = 20)
    private String status = "CONFIRMED";

    @Column(name = "mat_number", updatable = false)
    private Integer matNumber;

    @Column(name = "cancelled_at", insertable = false, updatable = false)
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;
}
