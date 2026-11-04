package com.company.yoga.schedule.online;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the outbox rows written by database triggers (V12) to the streaming provider.
 * Each row is locked while its provider call runs, so the job and an on-demand join never act twice.
 */
@Service
@Slf4j
public class OnlineMeetingSyncService {

    private static final int BATCH = 20;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LiveMeetingProvider provider;
    private final String registrantEmailDomain;

    public OnlineMeetingSyncService(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, LiveMeetingProvider provider,
                                    @Value("${yoga.online.zoom.registrant-email-domain:students.anyen.invalid}") String registrantEmailDomain) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.provider = provider;
        this.registrantEmailDomain = registrantEmailDomain;
    }

    public boolean isConfigured() {
        return provider.isConfigured();
    }

    public boolean personalLinks() {
        return provider.personalLinks();
    }

    /** Called by the job: meetings first, so registrants of a new meeting can follow in the same run. */
    public void syncDue() {
        if (!provider.isConfigured()) return;
        for (UUID id : jdbc.queryForList("""
                SELECT id FROM yoga.online_meetings
                WHERE status IN ('PENDING','UPDATE_PENDING','CANCEL_PENDING') AND next_attempt_at<=CURRENT_TIMESTAMP
                ORDER BY next_attempt_at LIMIT :n""", Map.of("n", BATCH), UUID.class)) {
            syncMeeting(id, false);
        }
        for (UUID id : jdbc.queryForList("""
                SELECT r.id FROM yoga.online_meeting_registrants r JOIN yoga.online_meetings m ON m.id=r.meeting_id
                WHERE r.status IN ('PENDING','REMOVE_PENDING') AND r.next_attempt_at<=CURRENT_TIMESTAMP
                  AND (r.status='REMOVE_PENDING' OR m.provider_meeting_id IS NOT NULL)
                ORDER BY r.next_attempt_at LIMIT :n""", Map.of("n", BATCH), UUID.class)) {
            syncRegistrant(id, false);
        }
    }

    /** Retry a class's meeting now (staff action), ignoring the backoff. */
    public void syncScheduleNow(UUID scheduleId) {
        jdbc.queryForList("SELECT id FROM yoga.online_meetings WHERE schedule_id=:s", Map.of("s", scheduleId), UUID.class)
                .forEach(id -> syncMeeting(id, true));
    }

    /**
     * Join link of a booking, creating the meeting and the registrant on the spot when the job has not done it yet.
     * Empty when the provider is unavailable.
     */
    public Optional<String> ensureJoinUrl(UUID bookingId) {
        var row = jdbc.queryForList("""
                SELECT r.id AS registrant_id, r.status, r.join_url, m.id AS meeting_id, m.provider_meeting_id
                FROM yoga.online_meeting_registrants r JOIN yoga.online_meetings m ON m.id=r.meeting_id
                WHERE r.booking_id=:b""", Map.of("b", bookingId));
        if (row.isEmpty()) return Optional.empty();
        Map<String, Object> r = row.get(0);
        if ("REGISTERED".equals(r.get("status"))) return Optional.of((String) r.get("join_url"));
        if (!"PENDING".equals(r.get("status")) || !provider.isConfigured()) return Optional.empty();
        if (r.get("provider_meeting_id") == null) syncMeeting((UUID) r.get("meeting_id"), true);
        syncRegistrant((UUID) r.get("registrant_id"), true);
        return jdbc.queryForList("SELECT join_url FROM yoga.online_meeting_registrants WHERE id=:id AND status='REGISTERED'",
                Map.of("id", r.get("registrant_id")), String.class).stream().findFirst();
    }

    public Optional<String> hostStartUrl(String meetingId) {
        if (!provider.isConfigured() || meetingId == null) return Optional.empty();
        try {
            return Optional.of(provider.hostStartUrl(meetingId));
        } catch (LiveMeetingProvider.ProviderException ex) {
            log.warn("Could not fetch host link for meeting {}: {}", meetingId, ex.getMessage());
            return Optional.empty();
        }
    }

    void syncMeeting(UUID id, boolean now) {
        transactions.executeWithoutResult(tx -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT m.status, m.provider_meeting_id, m.sync_attempts, s.start_time, s.end_time, s.status AS schedule_status,
                           COALESCE(cc.name, ct.name) AS class_name, s.session_number, r.live_host_account, u.full_name AS instructor
                    FROM yoga.online_meetings m
                    JOIN yoga.class_schedules s ON s.id=m.schedule_id
                    JOIN yoga.class_types ct ON ct.id=s.class_type_id
                    LEFT JOIN yoga.course_classes cc ON cc.id=s.course_class_id
                    JOIN yoga.rooms r ON r.id=s.room_id
                    JOIN yoga.users u ON u.id=s.instructor_id
                    WHERE m.id=:id AND m.status IN ('PENDING','UPDATE_PENDING','CANCEL_PENDING')
                      AND (:now OR m.next_attempt_at<=CURRENT_TIMESTAMP)
                    FOR UPDATE OF m SKIP LOCKED""", Map.of("id", id, "now", now));
            if (rows.isEmpty()) return;
            Map<String, Object> m = rows.get(0);
            String status = (String) m.get("status");
            String meetingId = (String) m.get("provider_meeting_id");
            Instant start = instant(m.get("start_time"));
            Instant end = instant(m.get("end_time"));
            try {
                switch (status) {
                    case "PENDING" -> {
                        if (!end.isAfter(Instant.now())) {
                            done(id, "CANCELLED", null, "Buổi học đã kết thúc trước khi tạo được phòng Zoom");
                            return;
                        }
                        String created = provider.createMeeting(spec(m, start, end));
                        done(id, "READY", created, null);
                    }
                    case "UPDATE_PENDING" -> {
                        provider.updateMeeting(meetingId, spec(m, start, end));
                        done(id, "READY", null, null);
                    }
                    case "CANCEL_PENDING" -> {
                        if (meetingId != null) provider.deleteMeeting(meetingId);
                        done(id, "CANCELLED", null, null);
                    }
                    default -> { }
                }
            } catch (LiveMeetingProvider.ProviderException ex) {
                log.warn("Online meeting {} sync failed ({}): {}", id, status, ex.getMessage());
                retry("online_meetings", id, ((Number) m.get("sync_attempts")).intValue(), ex.getMessage());
            }
        });
    }

    void syncRegistrant(UUID id, boolean now) {
        transactions.executeWithoutResult(tx -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT r.status, r.booking_id, r.provider_registrant_id, r.sync_attempts,
                           m.status AS meeting_status, m.provider_meeting_id, u.full_name
                    FROM yoga.online_meeting_registrants r
                    JOIN yoga.online_meetings m ON m.id=r.meeting_id
                    JOIN yoga.bookings b ON b.id=r.booking_id
                    JOIN yoga.users u ON u.id=b.student_id
                    WHERE r.id=:id AND r.status IN ('PENDING','REMOVE_PENDING')
                      AND (:now OR r.next_attempt_at<=CURRENT_TIMESTAMP)
                    FOR UPDATE OF r SKIP LOCKED""", Map.of("id", id, "now", now));
            if (rows.isEmpty()) return;
            Map<String, Object> r = rows.get(0);
            String meetingId = (String) r.get("provider_meeting_id");
            String meetingStatus = (String) r.get("meeting_status");
            boolean meetingGone = "CANCELLED".equals(meetingStatus) || "CANCEL_PENDING".equals(meetingStatus);
            try {
                if ("REMOVE_PENDING".equals(r.get("status"))) {
                    if (!meetingGone && meetingId != null) provider.removeRegistrant(meetingId, (String) r.get("provider_registrant_id"));
                    jdbc.update("UPDATE yoga.online_meeting_registrants SET status='REMOVED',last_error=NULL WHERE id=:id", Map.of("id", id));
                    return;
                }
                if (meetingGone || meetingId == null) return;
                UUID bookingId = (UUID) r.get("booking_id");
                var registrant = provider.addRegistrant(meetingId, new LiveMeetingProvider.RegistrantSpec(
                        "b-" + bookingId.toString().replace("-", "") + "@" + registrantEmailDomain,
                        firstName((String) r.get("full_name")), null));
                jdbc.update("""
                        UPDATE yoga.online_meeting_registrants SET status='REGISTERED',provider_registrant_id=:rid,join_url=:url,last_error=NULL
                        WHERE id=:id""", Map.of("id", id, "rid", registrant.registrantId(), "url", registrant.joinUrl()));
            } catch (LiveMeetingProvider.ProviderException ex) {
                log.warn("Online registrant {} sync failed: {}", id, ex.getMessage());
                retry("online_meeting_registrants", id, ((Number) r.get("sync_attempts")).intValue(), ex.getMessage());
            }
        });
    }

    private void done(UUID id, String status, String providerMeetingId, String note) {
        jdbc.update("""
                UPDATE yoga.online_meetings SET status=:status,provider_meeting_id=COALESCE(:pid,provider_meeting_id),last_error=:note
                WHERE id=:id""", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", id).addValue("status", status).addValue("pid", providerMeetingId).addValue("note", note));
    }

    private void retry(String table, UUID id, int attempts, String error) {
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), 30L << Math.min(attempts, 10));
        jdbc.update("UPDATE yoga." + table + " SET sync_attempts=sync_attempts+1,last_error=:e,"
                        + "next_attempt_at=CURRENT_TIMESTAMP+make_interval(secs=>:s) WHERE id=:id",
                Map.of("id", id, "e", error == null ? "unknown" : error, "s", seconds));
    }

    private static LiveMeetingProvider.MeetingSpec spec(Map<String, Object> m, Instant start, Instant end) {
        Object session = m.get("session_number");
        String topic = m.get("class_name") + (session != null ? " – Buổi " + session : "") + " (HLV " + m.get("instructor") + ")";
        return new LiveMeetingProvider.MeetingSpec((String) m.get("live_host_account"), topic, start,
                (int) Math.max(1, Duration.between(start, end).toMinutes()));
    }

    private static String firstName(String fullName) {
        String name = fullName == null || fullName.isBlank() ? "Học viên" : fullName.trim();
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        return (Instant) value;
    }
}
