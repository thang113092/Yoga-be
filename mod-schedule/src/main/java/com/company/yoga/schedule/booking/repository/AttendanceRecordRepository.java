package com.company.yoga.schedule.booking.repository;

import com.company.yoga.schedule.booking.entity.AttendanceRecordEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecordEntity, UUID> {

    Optional<AttendanceRecordEntity> findByBookingId(UUID bookingId);

    Optional<AttendanceRecordEntity> findByScheduleIdAndStudentId(UUID scheduleId, UUID studentId);

    List<AttendanceRecordEntity> findByScheduleId(UUID scheduleId);

    List<AttendanceRecordEntity> findByStudentIdOrderByCheckedInAtDesc(UUID studentId);
}
