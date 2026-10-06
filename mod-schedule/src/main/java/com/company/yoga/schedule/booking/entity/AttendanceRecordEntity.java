package com.company.yoga.schedule.booking.entity;

import com.company.yoga.common.entity.BaseCreationAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "attendance_records", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceRecordEntity extends BaseCreationAuditEntity {

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "membership_id", nullable = false, updatable = false)
    private UUID membershipId;

    @Column(name = "booking_id", nullable = false, unique = true, updatable = false)
    private UUID bookingId;

    @Column(name = "checked_in_branch_id", nullable = false, updatable = false)
    private UUID checkedInBranchId;

    @Column(name = "checked_in_at", nullable = false, updatable = false)
    private Instant checkedInAt = Instant.now();

    @Column(name = "checked_in_by")
    private UUID checkedInBy;

    @Column(name = "check_in_method", nullable = false, length = 20)
    private String checkInMethod = "QR_SCAN";

    @Column(name = "attendance_status", nullable = false, length = 20)
    private String attendanceStatus = "PRESENT";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
