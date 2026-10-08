package com.company.yoga.schedule.booking.repository;

import com.company.yoga.schedule.booking.entity.BookingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {
    @org.springframework.data.jpa.repository.Query(value = "SELECT count(*) FROM yoga.bookings WHERE schedule_id = :scheduleId AND student_id = :studentId AND status <> 'CANCELLED'", nativeQuery = true)
    long activeBookingCount(@org.springframework.data.repository.query.Param("scheduleId") UUID scheduleId, @org.springframework.data.repository.query.Param("studentId") UUID studentId);

    @org.springframework.data.jpa.repository.Query(value = "SELECT count(*) FROM yoga.membership_freezes WHERE membership_id = :id AND status IN ('ACTIVE', 'COMPLETED') AND :day BETWEEN freeze_start AND freeze_end", nativeQuery = true)
    long freezeCount(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("day") java.time.LocalDate day);
    Optional<BookingEntity> findByScheduleIdAndStudentIdAndStatus(UUID scheduleId, UUID studentId, String status);
    @org.springframework.data.jpa.repository.Query("SELECT new com.company.yoga.schedule.booking.dto.BookingDto$StudentBookingDetailResp(b.id, b.bookingCode, b.scheduleId, ct.name, u.fullName, r.name, br.name, s.startTime, s.endTime, b.matNumber, b.status, b.bookingTime, CASE WHEN b.courseEnrollmentId IS NULL AND b.status = 'CONFIRMED' AND :now < s.startTime THEN true ELSE false END) FROM BookingEntity b JOIN ClassScheduleEntity s ON s.id = b.scheduleId JOIN ClassTypeEntity ct ON ct.id = s.classTypeId JOIN UserEntity u ON u.id = s.instructorId JOIN RoomEntity r ON r.id = s.roomId JOIN BranchEntity br ON br.id = s.branchId WHERE b.studentId = :studentId ORDER BY s.startTime DESC")
    List<com.company.yoga.schedule.booking.dto.BookingDto.StudentBookingDetailResp> findStudentDetails(@org.springframework.data.repository.query.Param("studentId") UUID studentId, @org.springframework.data.repository.query.Param("now") java.time.Instant now);
    @org.springframework.data.jpa.repository.Query("SELECT b.matNumber FROM BookingEntity b WHERE b.scheduleId = :id AND b.status <> 'CANCELLED' AND b.matNumber IS NOT NULL")
    List<Integer> occupiedMats(@org.springframework.data.repository.query.Param("id") UUID id);
    List<BookingEntity> findByStudentIdOrderByCreatedAtDesc(UUID studentId);
    Optional<BookingEntity> findByBookingCode(String bookingCode);

    @org.springframework.data.jpa.repository.Query("SELECT new com.company.yoga.schedule.booking.dto.BookingDto$AttendeeResp(" +
            "b.id, b.bookingCode, u.id, u.fullName, u.phone, u.email, u.gender, b.matNumber, b.status, b.bookingTime, att.checkedInAt) " +
            "FROM BookingEntity b " +
            "JOIN com.company.yoga.identity.account.entity.UserEntity u ON u.id = b.studentId " +
            "LEFT JOIN AttendanceRecordEntity att ON att.bookingId = b.id " +
            "WHERE b.scheduleId = :scheduleId AND b.status <> 'CANCELLED' " +
            "ORDER BY b.createdAt ASC")
    List<com.company.yoga.schedule.booking.dto.BookingDto.AttendeeResp> findScheduleAttendees(@org.springframework.data.repository.query.Param("scheduleId") UUID scheduleId);

    @org.springframework.data.jpa.repository.Query("SELECT new com.company.yoga.schedule.booking.dto.BookingDto$WorkoutHistoryResp(" +
            "b.id, b.bookingCode, b.scheduleId, ct.name, ct.intensityLevel, u.fullName, r.name, br.name, br.id, " +
            "s.startTime, s.endTime, ct.defaultDurationMinutes, b.matNumber, b.status, " +
            "COALESCE(att.checkedInAt, b.bookingTime), " +
            "COALESCE(att.checkInMethod, 'QR_SCAN'), " +
            "COALESCE(att.attendanceStatus, 'PRESENT'), " +
            "m.membershipCode, att.notes) " +
            "FROM BookingEntity b " +
            "JOIN ClassScheduleEntity s ON s.id = b.scheduleId " +
            "JOIN ClassTypeEntity ct ON ct.id = s.classTypeId " +
            "JOIN com.company.yoga.identity.account.entity.UserEntity u ON u.id = s.instructorId " +
            "JOIN RoomEntity r ON r.id = s.roomId " +
            "JOIN com.company.yoga.branch.facility.entity.BranchEntity br ON br.id = s.branchId " +
            "LEFT JOIN com.company.yoga.membership.plan.entity.MembershipEntity m ON m.id = b.membershipId " +
            "LEFT JOIN AttendanceRecordEntity att ON att.bookingId = b.id " +
            "WHERE b.studentId = :studentId AND (b.status = 'ATTENDED' OR att.id IS NOT NULL) " +
            "ORDER BY s.startTime DESC")
    List<com.company.yoga.schedule.booking.dto.BookingDto.WorkoutHistoryResp> findStudentWorkoutHistory(
            @org.springframework.data.repository.query.Param("studentId") UUID studentId
    );

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(b) > 0 FROM BookingEntity b JOIN ClassScheduleEntity s ON s.id = b.scheduleId " +
            "WHERE b.studentId = :studentId AND b.status = 'CONFIRMED' AND s.status <> 'CANCELLED' " +
            "AND (:excludeScheduleId IS NULL OR s.id <> :excludeScheduleId) " +
            "AND s.startTime < :endTime AND s.endTime > :startTime")
    boolean existsStudentTimeConflict(
            @org.springframework.data.repository.query.Param("studentId") UUID studentId,
            @org.springframework.data.repository.query.Param("excludeScheduleId") UUID excludeScheduleId,
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime
    );
}
