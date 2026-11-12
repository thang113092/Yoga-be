package com.company.yoga.content.report;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Online learning report: live classes (online sessions and online seats of hybrid classes), online package and
 * online course revenue, and the video library. Super admins see every branch; branch managers their own branches.
 */
@Service
@RequiredArgsConstructor
public class OnlineReportService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public record Kpi(long sessions, long heldSessions, long bookings, long joins, BigDecimal attendanceRate, long uniqueStudents,
                      BigDecimal packageRevenue, BigDecimal courseRevenue, long activeOnlinePasses, long videoPlays, long videoViewers,
                      long videoCompletions, BigDecimal videoHours) {}

    public record BranchRow(UUID branchId, String branchName, long sessions, long bookings, long joins, BigDecimal attendanceRate) {}

    public record DailyRow(LocalDate date, long joins, long videoPlays) {}

    public record PlanRow(String planName, String accessScope, long sold, BigDecimal revenue) {}

    public record VideoRow(UUID videoId, String title, long plays, long viewers, long completions, BigDecimal hours) {}

    public record Report(LocalDate startDate, LocalDate endDate, UUID branchId, Kpi kpi, List<BranchRow> branches, List<DailyRow> daily,
                         List<PlanRow> plans, List<VideoRow> topVideos) {}

    public Report report(LocalDate startDate, LocalDate endDate, UUID branchId) {
        UUID branch = branchScope(branchId);
        LocalDate today = LocalDate.now(VIETNAM);
        LocalDate end = endDate != null ? endDate : today;
        LocalDate start = startDate != null ? startDate : end.minusDays(29);
        if (start.isAfter(end)) { LocalDate t = start; start = end; end = t; }
        if (start.plusDays(366).isBefore(end)) start = end.minusDays(366);
        Instant from = start.atStartOfDay(VIETNAM).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(VIETNAM).toInstant();
        var p = new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("branch", branch).addValue("today", java.sql.Date.valueOf(today));

        // Bookings that use an online seat (online classes and the online seats of hybrid classes).
        String onlineBookings = """
                FROM yoga.bookings b JOIN yoga.class_schedules s ON s.id=b.schedule_id
                WHERE s.status<>'CANCELLED' AND s.start_time>=:from AND s.start_time<:to AND s.start_time<CURRENT_TIMESTAMP
                  AND b.status IN ('CONFIRMED','ATTENDED','NO_SHOW') AND """ + ONLINE_SEAT + """
                  AND (CAST(:branch AS uuid) IS NULL OR s.branch_id=CAST(:branch AS uuid))""";
        Map<String, Object> live = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM yoga.class_schedules s WHERE s.status<>'CANCELLED' AND s.delivery_mode IN ('ONLINE','HYBRID')
                          AND s.start_time>=:from AND s.start_time<:to AND (CAST(:branch AS uuid) IS NULL OR s.branch_id=CAST(:branch AS uuid))) AS sessions,
                       (SELECT count(*) FROM yoga.class_schedules s WHERE s.status<>'CANCELLED' AND s.delivery_mode IN ('ONLINE','HYBRID')
                          AND s.start_time>=:from AND s.start_time<:to AND s.start_time<CURRENT_TIMESTAMP
                          AND (CAST(:branch AS uuid) IS NULL OR s.branch_id=CAST(:branch AS uuid))) AS held,
                       (SELECT count(*) """ + onlineBookings + """
                       ) AS bookings,
                       (SELECT count(*) """ + onlineBookings + """
                          AND EXISTS(SELECT 1 FROM yoga.attendance_records a WHERE a.booking_id=b.id AND a.check_in_method='ONLINE_JOIN')) AS joins,
                       (SELECT count(DISTINCT b.student_id) """ + onlineBookings + """
                          AND EXISTS(SELECT 1 FROM yoga.attendance_records a WHERE a.booking_id=b.id AND a.check_in_method='ONLINE_JOIN')) AS students
                """, p);
        long bookings = num(live.get("bookings"));
        long joins = num(live.get("joins"));

        BigDecimal packageRevenue = money(jdbc.queryForObject("""
                SELECT COALESCE(sum(pay.amount),0) FROM yoga.payments pay
                WHERE pay.payment_status='SUCCESS' AND pay.payment_time>=:from AND pay.payment_time<:to
                  AND (CAST(:branch AS uuid) IS NULL OR pay.branch_id=CAST(:branch AS uuid))
                  AND EXISTS(SELECT 1 FROM yoga.order_items i JOIN yoga.membership_plans mp ON mp.id=i.item_id
                             WHERE i.order_id=pay.order_id AND i.item_type='MEMBERSHIP_PLAN' AND mp.access_scope IN ('ONLINE','HYBRID'))""",
                p, BigDecimal.class));
        BigDecimal courseRevenue = money(jdbc.queryForObject("""
                SELECT COALESCE(sum(pay.amount),0) FROM yoga.payments pay
                WHERE pay.payment_status='SUCCESS' AND pay.payment_time>=:from AND pay.payment_time<:to
                  AND (CAST(:branch AS uuid) IS NULL OR pay.branch_id=CAST(:branch AS uuid))
                  AND EXISTS(SELECT 1 FROM yoga.order_items i JOIN yoga.course_classes c ON c.id=i.course_class_id
                             WHERE i.order_id=pay.order_id AND i.item_type='COURSE_CLASS' AND c.delivery_mode='ONLINE')""",
                p, BigDecimal.class));
        Long passes = jdbc.queryForObject("""
                SELECT count(*) FROM yoga.memberships m WHERE m.status='ACTIVE' AND m.access_scope IN ('ONLINE','HYBRID')
                  AND (m.end_date IS NULL OR m.end_date>=:today)
                  AND (CAST(:branch AS uuid) IS NULL OR m.registered_branch_id=CAST(:branch AS uuid))""", p, Long.class);

        // The video library belongs to the whole chain: no branch filter.
        Map<String, Object> video = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM yoga.video_playback_sessions WHERE started_at>=:from AND started_at<:to) AS plays,
                       (SELECT count(DISTINCT student_id) FROM yoga.video_playback_sessions WHERE started_at>=:from AND started_at<:to) AS viewers,
                       (SELECT count(*) FROM yoga.video_watch_progress WHERE completed_at>=:from AND completed_at<:to) AS completions,
                       (SELECT COALESCE(sum(watched_seconds),0) FROM yoga.video_watch_progress WHERE updated_at>=:from AND updated_at<:to) AS seconds""", p);

        List<BranchRow> branches = jdbc.query("""
                SELECT br.id,br.name,
                  (SELECT count(*) FROM yoga.class_schedules s WHERE s.branch_id=br.id AND s.status<>'CANCELLED' AND s.delivery_mode IN ('ONLINE','HYBRID')
                     AND s.start_time>=:from AND s.start_time<:to) AS sessions,
                  (SELECT count(*) FROM yoga.bookings b JOIN yoga.class_schedules s ON s.id=b.schedule_id WHERE s.branch_id=br.id AND s.status<>'CANCELLED'
                     AND s.start_time>=:from AND s.start_time<:to AND s.start_time<CURRENT_TIMESTAMP AND b.status IN ('CONFIRMED','ATTENDED','NO_SHOW') AND """
                + ONLINE_SEAT + """
                  ) AS bookings,
                  (SELECT count(*) FROM yoga.attendance_records a JOIN yoga.class_schedules s ON s.id=a.schedule_id WHERE s.branch_id=br.id
                     AND s.status<>'CANCELLED' AND s.start_time>=:from AND s.start_time<:to AND a.check_in_method='ONLINE_JOIN') AS joins
                FROM yoga.branches br WHERE br.is_active AND NOT br.is_virtual AND (CAST(:branch AS uuid) IS NULL OR br.id=CAST(:branch AS uuid))
                ORDER BY br.name""", p,
                (rs, i) -> new BranchRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("sessions"), rs.getLong("bookings"),
                        rs.getLong("joins"), rate(rs.getLong("joins"), rs.getLong("bookings"))));

        Map<LocalDate, long[]> days = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) days.put(d, new long[2]);
        jdbc.query("""
                SELECT (a.checked_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE AS d,count(*) AS n FROM yoga.attendance_records a
                JOIN yoga.class_schedules s ON s.id=a.schedule_id
                WHERE a.check_in_method='ONLINE_JOIN' AND a.checked_in_at>=:from AND a.checked_in_at<:to
                  AND (CAST(:branch AS uuid) IS NULL OR s.branch_id=CAST(:branch AS uuid)) GROUP BY 1""", p,
                rs -> { long[] v = days.get(rs.getDate("d").toLocalDate()); if (v != null) v[0] = rs.getLong("n"); });
        jdbc.query("""
                SELECT (started_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE AS d,count(*) AS n FROM yoga.video_playback_sessions
                WHERE started_at>=:from AND started_at<:to GROUP BY 1""", p,
                rs -> { long[] v = days.get(rs.getDate("d").toLocalDate()); if (v != null) v[1] = rs.getLong("n"); });
        List<DailyRow> daily = new ArrayList<>();
        days.forEach((d, v) -> daily.add(new DailyRow(d, v[0], v[1])));

        List<PlanRow> plans = jdbc.query("""
                SELECT mp.name,mp.access_scope,count(DISTINCT pay.order_id) AS sold,COALESCE(sum(pay.amount),0) AS revenue
                FROM yoga.payments pay JOIN yoga.order_items i ON i.order_id=pay.order_id AND i.item_type='MEMBERSHIP_PLAN'
                JOIN yoga.membership_plans mp ON mp.id=i.item_id
                WHERE pay.payment_status='SUCCESS' AND pay.payment_time>=:from AND pay.payment_time<:to AND mp.access_scope IN ('ONLINE','HYBRID')
                  AND (CAST(:branch AS uuid) IS NULL OR pay.branch_id=CAST(:branch AS uuid))
                GROUP BY mp.name,mp.access_scope ORDER BY revenue DESC""", p,
                (rs, i) -> new PlanRow(rs.getString("name"), rs.getString("access_scope"), rs.getLong("sold"), money(rs.getBigDecimal("revenue"))));

        List<VideoRow> topVideos = jdbc.query("""
                SELECT v.id,v.title,
                  (SELECT count(*) FROM yoga.video_playback_sessions x WHERE x.video_id=v.id AND x.started_at>=:from AND x.started_at<:to) AS plays,
                  (SELECT count(DISTINCT x.student_id) FROM yoga.video_playback_sessions x WHERE x.video_id=v.id AND x.started_at>=:from AND x.started_at<:to) AS viewers,
                  (SELECT count(*) FROM yoga.video_watch_progress w WHERE w.video_id=v.id AND w.completed_at>=:from AND w.completed_at<:to) AS completions,
                  (SELECT COALESCE(sum(w.watched_seconds),0) FROM yoga.video_watch_progress w WHERE w.video_id=v.id AND w.updated_at>=:from AND w.updated_at<:to) AS seconds
                FROM yoga.video_lessons v WHERE v.status IN ('PUBLISHED','READY','ARCHIVED')
                ORDER BY plays DESC, viewers DESC, v.title LIMIT 10""", p,
                (rs, i) -> new VideoRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getLong("plays"), rs.getLong("viewers"),
                        rs.getLong("completions"), hours(rs.getLong("seconds"))))
                .stream().filter(v -> v.plays() > 0 || v.completions() > 0).toList();

        Kpi kpi = new Kpi(num(live.get("sessions")), num(live.get("held")), bookings, joins, rate(joins, bookings), num(live.get("students")),
                packageRevenue, courseRevenue, passes == null ? 0 : passes, num(video.get("plays")), num(video.get("viewers")),
                num(video.get("completions")), hours(num(video.get("seconds"))));
        return new Report(start, end, branch, kpi, branches, daily, plans, topVideos);
    }

    /** A booking that uses an online seat: every booking of an online class, online seats of hybrid classes (V19). */
    static final String ONLINE_SEAT = "b.attendance_mode='ONLINE'";

    private UUID branchScope(UUID requested) {
        UserEntity actor = accessPolicy.actor();
        String role = accessPolicy.role(actor);
        if ("SUPER_ADMIN".equals(role)) return requested;
        if (!"BRANCH_MANAGER".equals(role)) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        UUID branch = requested != null ? requested : actor.getHomeBranchId();
        if (branch == null) {
            branch = jdbc.queryForList("SELECT branch_id FROM yoga.user_branches WHERE user_id=:me ORDER BY branch_id LIMIT 1",
                    Map.of("me", actor.getId()), UUID.class).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        }
        accessPolicy.requireStaffBranch(branch);
        return branch;
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
    }

    static BigDecimal rate(long part, long whole) {
        if (whole == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds / 3600.0).setScale(1, RoundingMode.HALF_UP);
    }
}
