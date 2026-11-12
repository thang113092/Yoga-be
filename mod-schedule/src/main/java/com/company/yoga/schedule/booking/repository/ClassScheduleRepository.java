package com.company.yoga.schedule.booking.repository;

import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassScheduleRepository extends JpaRepository<ClassScheduleEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ClassScheduleEntity s WHERE s.id = :id")
    Optional<ClassScheduleEntity> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT s FROM ClassScheduleEntity s WHERE (:branchId IS NULL OR s.branchId = :branchId) AND s.startTime >= :start AND s.startTime < :end ORDER BY s.startTime")
    List<ClassScheduleEntity> findInRange(@Param("branchId") UUID branchId, @Param("start") Instant start, @Param("end") Instant end);

    @Query(value = "SELECT booked_count FROM yoga.class_schedules WHERE id = :id", nativeQuery = true)
    Integer currentBookedCount(@Param("id") UUID id);

    @Query(value = "SELECT online_booked_count FROM yoga.class_schedules WHERE id = :id", nativeQuery = true)
    Integer currentOnlineBookedCount(@Param("id") UUID id);

    @Query("SELECT new com.company.yoga.schedule.booking.dto.ScheduleDto$Resp(s.id, s.branchId, b.name, s.roomId, s.instructorId, COALESCE(cc.name, ct.name), u.fullName, r.name, s.startTime, s.endTime, s.maxCapacity, s.bookedCount, s.maxCapacity - s.bookedCount, s.status, s.courseClassId, s.sessionNumber, s.deliveryMode, s.liveRoomId, s.onlineCapacity, s.onlineBookedCount) " +
            "FROM ClassScheduleEntity s " +
            "JOIN BranchEntity b ON b.id = s.branchId " +
            "LEFT JOIN CourseClassEntity cc ON cc.id = s.courseClassId " +
            "JOIN ClassTypeEntity ct ON ct.id = s.classTypeId " +
            "JOIN UserEntity u ON u.id = s.instructorId " +
            "JOIN RoomEntity r ON r.id = s.roomId " +
            "WHERE u.isActive = true " +
            "AND (:branchId IS NULL OR s.branchId = :branchId) " +
            "AND (:instructorId IS NULL OR s.instructorId = :instructorId) " +
            "AND (:mode IS NULL OR s.deliveryMode = :mode) " +
            "AND s.startTime >= :start AND s.startTime < :end ORDER BY s.startTime")
    List<com.company.yoga.schedule.booking.dto.ScheduleDto.Resp> findSummaries(
            @Param("branchId") UUID branchId,
            @Param("instructorId") UUID instructorId,
            @Param("mode") String mode,
            @Param("start") Instant start,
            @Param("end") Instant end
    );
    List<ClassScheduleEntity> findByBranchIdAndStartTimeBetween(UUID branchId, Instant start, Instant end);
    boolean existsByClassTypeId(UUID classTypeId);

    @Query("SELECT COUNT(s) > 0 FROM ClassScheduleEntity s WHERE s.roomId = :roomId AND s.status <> 'CANCELLED' AND s.startTime < :endTime AND s.endTime > :startTime")
    boolean existsOverlappingRoomSchedule(@Param("roomId") UUID roomId, @Param("startTime") Instant startTime, @Param("endTime") Instant endTime);

    /** One Zoom user hosts one meeting at a time; rooms without a host share the default account (V13). */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM yoga.class_schedules s JOIN yoga.rooms r ON r.id = COALESCE(s.live_room_id, s.room_id) " +
            "WHERE s.delivery_mode IN ('ONLINE','HYBRID') AND s.status <> 'CANCELLED' AND s.start_time < :endTime AND s.end_time > :startTime " +
            "AND COALESCE(lower(r.live_host_account), '') = (SELECT COALESCE(lower(live_host_account), '') FROM yoga.rooms WHERE id = :roomId))", nativeQuery = true)
    boolean existsLiveHostOverlap(@Param("roomId") UUID roomId, @Param("startTime") Instant startTime, @Param("endTime") Instant endTime);

    @Query("SELECT COUNT(s) > 0 FROM ClassScheduleEntity s WHERE s.instructorId = :instructorId AND s.status <> 'CANCELLED' AND s.startTime < :endTime AND s.endTime > :startTime")
    boolean existsOverlappingInstructorSchedule(@Param("instructorId") UUID instructorId, @Param("startTime") Instant startTime, @Param("endTime") Instant endTime);
}
