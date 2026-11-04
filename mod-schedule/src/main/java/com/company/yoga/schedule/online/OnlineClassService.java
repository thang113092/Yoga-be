package com.company.yoga.schedule.online;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.entity.AttendanceRecordEntity;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.repository.AttendanceRecordRepository;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Joining an online class is the student's check-in: the link is handed out and attendance recorded together. */
@Service
@Slf4j
public class OnlineClassService {

    static final String ONLINE = "ONLINE";
    private static final Duration HOST_LINK_LEAD = Duration.ofHours(1);
    /** The database accepts check-ins from 30 minutes before class (validate_checkin). */
    private static final Duration MAX_JOIN_LEAD = Duration.ofMinutes(30);

    private final AccessPolicy accessPolicy;
    private final BookingRepository bookings;
    private final ClassScheduleRepository schedules;
    private final AttendanceRecordRepository attendance;
    private final OnlineMeetingSyncService sync;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration joinLead;

    public OnlineClassService(AccessPolicy accessPolicy, BookingRepository bookings, ClassScheduleRepository schedules,
                              AttendanceRecordRepository attendance, OnlineMeetingSyncService sync, NamedParameterJdbcTemplate jdbc,
                              TransactionTemplate transactions, @Value("${yoga.online.join-window-minutes:15}") long joinWindowMinutes) {
        this.accessPolicy = accessPolicy;
        this.bookings = bookings;
        this.schedules = schedules;
        this.attendance = attendance;
        this.sync = sync;
        this.jdbc = jdbc;
        this.transactions = transactions;
        Duration lead = Duration.ofMinutes(Math.max(0, joinWindowMinutes));
        this.joinLead = lead.compareTo(MAX_JOIN_LEAD) > 0 ? MAX_JOIN_LEAD : lead;
    }

    /** Not transactional on purpose: provider calls run in their own short transactions. */
    public OnlineDto.JoinResp join(UUID bookingId) {
        UserEntity actor = accessPolicy.actor();
        BookingEntity booking = bookings.findById(bookingId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.BOOKING_NOT_FOUND));
        if (!booking.getStudentId().equals(actor.getId())) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        ClassScheduleEntity schedule = schedules.findById(booking.getScheduleId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        if (!ONLINE.equals(schedule.getDeliveryMode())) throw new BusinessException(ScheduleResultCodes.NOT_ONLINE_CLASS);
        if ("CANCELLED".equals(schedule.getStatus()) || !Set.of("CONFIRMED", "ATTENDED").contains(booking.getStatus())) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_NOT_CONFIRMED);
        }
        Instant now = Instant.now();
        if (now.isBefore(schedule.getStartTime().minus(joinLead))) throw new BusinessException(ScheduleResultCodes.ONLINE_JOIN_NOT_OPEN);
        if (!now.isBefore(schedule.getEndTime())) throw new BusinessException(ScheduleResultCodes.ONLINE_CLASS_ENDED);
        if (!sync.isConfigured()) throw new BusinessException(ScheduleResultCodes.ONLINE_NOT_CONFIGURED);

        String joinUrl = sync.ensureJoinUrl(bookingId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.ONLINE_ROOM_NOT_READY));
        recordJoin(bookingId, actor.getId());
        return new OnlineDto.JoinResp(bookingId, schedule.getId(), joinUrl, schedule.getStartTime(), schedule.getEndTime());
    }

    private void recordJoin(UUID bookingId, UUID studentId) {
        try {
            transactions.executeWithoutResult(tx -> {
                ClassScheduleEntity schedule = schedules.findByIdWithLock(
                        bookings.findById(bookingId).orElseThrow().getScheduleId()).orElseThrow();
                BookingEntity booking = bookings.findById(bookingId).orElseThrow();
                if ("CONFIRMED".equals(booking.getStatus()) && attendance.findByBookingId(bookingId).isEmpty()) {
                    AttendanceRecordEntity record = new AttendanceRecordEntity();
                    record.setScheduleId(schedule.getId());
                    record.setStudentId(studentId);
                    record.setMembershipId(booking.getMembershipId());
                    record.setCourseEnrollmentId(booking.getCourseEnrollmentId());
                    record.setBookingId(bookingId);
                    record.setCheckedInBranchId(schedule.getBranchId());
                    record.setCheckedInAt(Instant.now());
                    record.setCheckedInBy(studentId);
                    record.setCheckInMethod("ONLINE_JOIN");
                    record.setAttendanceStatus(Instant.now().isAfter(schedule.getStartTime().plus(Duration.ofMinutes(10))) ? "LATE" : "PRESENT");
                    attendance.saveAndFlush(record);
                    booking.setStatus("ATTENDED");
                    bookings.saveAndFlush(booking);
                }
                jdbc.update("""
                        UPDATE yoga.online_meeting_registrants SET first_joined_at=CURRENT_TIMESTAMP
                        WHERE booking_id=:b AND status='REGISTERED' AND first_joined_at IS NULL""", Map.of("b", bookingId));
            });
        } catch (DataIntegrityViolationException ex) {
            // A second device joined at the same moment and already recorded the attendance.
            log.debug("Concurrent online join for booking {}", bookingId);
        }
    }

    public OnlineDto.SessionResp session(UUID scheduleId) {
        ClassScheduleEntity schedule = onlineSchedule(scheduleId);
        accessPolicy.requireInstructorSchedule(schedule.getBranchId(), schedule.getInstructorId());
        UserEntity actor = accessPolicy.actor();
        String role = accessPolicy.role(actor);
        boolean canHost = actor.getId().equals(schedule.getInstructorId()) || Set.of("SUPER_ADMIN", "BRANCH_MANAGER").contains(role);

        var rows = jdbc.queryForList("""
                SELECT m.status, m.provider_meeting_id, m.last_error, m.sync_attempts, m.started_at, m.ended_at,
                  (SELECT count(*) FROM yoga.online_meeting_registrants r WHERE r.meeting_id=m.id AND r.status='REGISTERED') AS registered,
                  (SELECT count(*) FROM yoga.online_meeting_registrants r WHERE r.meeting_id=m.id AND r.status='PENDING') AS pending
                FROM yoga.online_meetings m WHERE m.schedule_id=:s""", Map.of("s", scheduleId));
        Instant hostOpens = schedule.getStartTime().minus(HOST_LINK_LEAD);
        if (rows.isEmpty()) {
            return new OnlineDto.SessionResp(scheduleId, sync.isConfigured(), "MISSING", null, 0, null, null, 0, 0, null, hostOpens,
                    sync.personalLinks());
        }
        Map<String, Object> m = rows.get(0);
        String status = (String) m.get("status");
        Instant now = Instant.now();
        String startUrl = null;
        if (canHost && Set.of("READY", "UPDATE_PENDING", "STARTED", "ENDED").contains(status)
                && !now.isBefore(hostOpens) && now.isBefore(schedule.getEndTime())) {
            startUrl = sync.hostStartUrl((String) m.get("provider_meeting_id")).orElse(null);
        }
        return new OnlineDto.SessionResp(scheduleId, sync.isConfigured(), status, (String) m.get("last_error"),
                ((Number) m.get("sync_attempts")).intValue(), instant(m.get("started_at")), instant(m.get("ended_at")),
                ((Number) m.get("registered")).longValue(), ((Number) m.get("pending")).longValue(), startUrl, hostOpens,
                sync.personalLinks());
    }

    /** Staff retry after fixing the Zoom setup, without waiting for the backoff. */
    public OnlineDto.SessionResp resync(UUID scheduleId) {
        ClassScheduleEntity schedule = onlineSchedule(scheduleId);
        accessPolicy.requireInstructorSchedule(schedule.getBranchId(), schedule.getInstructorId());
        if (!sync.isConfigured()) throw new BusinessException(ScheduleResultCodes.ONLINE_NOT_CONFIGURED);
        sync.syncScheduleNow(scheduleId);
        return session(scheduleId);
    }

    private ClassScheduleEntity onlineSchedule(UUID scheduleId) {
        ClassScheduleEntity schedule = schedules.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        if (!ONLINE.equals(schedule.getDeliveryMode())) throw new BusinessException(ScheduleResultCodes.NOT_ONLINE_CLASS);
        return schedule;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        return (Instant) value;
    }
}
