package com.company.yoga.schedule.booking.repository;

import com.company.yoga.schedule.booking.entity.WaitlistEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WaitlistRepository extends JpaRepository<WaitlistEntity, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntity w WHERE w.scheduleId = :scheduleId AND w.status = :status ORDER BY w.queuePosition")
    List<WaitlistEntity> findByScheduleIdAndStatusOrderByQueuePositionAsc(UUID scheduleId, String status);

    @Query("SELECT new com.company.yoga.schedule.booking.dto.WaitlistDto$StudentWaitlistResp(w.id, w.scheduleId, ct.name, u.fullName, r.name, br.name, s.startTime, s.endTime, w.queuePosition, w.status, w.createdAt) FROM WaitlistEntity w JOIN ClassScheduleEntity s ON s.id = w.scheduleId JOIN ClassTypeEntity ct ON ct.id = s.classTypeId JOIN UserEntity u ON u.id = s.instructorId JOIN RoomEntity r ON r.id = s.roomId JOIN BranchEntity br ON br.id = s.branchId WHERE w.studentId = :studentId ORDER BY w.createdAt DESC")
    List<com.company.yoga.schedule.booking.dto.WaitlistDto.StudentWaitlistResp> findStudentDetails(@Param("studentId") UUID studentId);
    List<WaitlistEntity> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    Optional<WaitlistEntity> findByScheduleIdAndStudentIdAndStatus(UUID scheduleId, UUID studentId, String status);

    @Query("SELECT COALESCE(MAX(w.queuePosition), 0) FROM WaitlistEntity w WHERE w.scheduleId = :scheduleId AND w.status = 'WAITING'")
    Integer findMaxQueuePositionByScheduleId(@Param("scheduleId") UUID scheduleId);

    long countByScheduleIdAndStatus(UUID scheduleId, String status);
}
