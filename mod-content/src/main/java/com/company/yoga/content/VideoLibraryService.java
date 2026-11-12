package com.company.yoga.content;

import static com.company.yoga.content.VideoAdminService.blankToNull;
import static com.company.yoga.content.VideoAdminService.instant;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.VideoHostingProvider.ProviderException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The student's video library. Watching needs a published video and either a free preview or an ONLINE/HYBRID pass
 * (database function {@code has_video_access}), the safety waiver, and a free device slot. Each open player holds a
 * playback session that expires unless the player reports progress.
 */
@Service
@Slf4j
public class VideoLibraryService {

    /** Students count as having watched a second only while the player reports; at most this much per report. */
    private static final int MAX_REPORT_SECONDS = 120;

    private static final String SELECT_LIBRARY = """
            SELECT v.id,v.title,v.description,v.level,v.duration_seconds,v.thumbnail_url,v.is_free_preview,v.published_at,
              v.contraindications,v.not_for_pregnancy,v.not_for_heart_bp,v.not_for_injuries,u.full_name AS instructor_name,
              (SELECT array_agg(t.name ORDER BY t.name) FROM yoga.video_lesson_tags x JOIN yoga.video_tags t ON t.id=x.tag_id WHERE x.video_id=v.id) AS tags,
              COALESCE(p.position_seconds,0) AS position_seconds,COALESCE(p.watched_seconds,0) AS watched_seconds,
              p.completed_at IS NOT NULL AS completed,p.updated_at AS progress_at,
              NOT yoga.has_video_access(:me,v.id) AS locked
            FROM yoga.video_lessons v LEFT JOIN yoga.users u ON u.id=v.instructor_id
            LEFT JOIN yoga.video_watch_progress p ON p.video_id=v.id AND p.student_id=:me
            """;

    /** Recordings of a course are listed only to students of that course (V20). */
    private static final String COURSE_VISIBLE = """
            (v.course_class_id IS NULL OR EXISTS(SELECT 1 FROM yoga.course_enrollments ce
              WHERE ce.course_class_id=v.course_class_id AND ce.student_id=:me AND ce.status IN ('ACTIVE','COMPLETED')))""";

    private final AccessPolicy accessPolicy;
    private final VideoHostingProvider provider;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxDevices;
    private final Duration tokenTtl;
    private final Duration heartbeat;
    private final String waiverVersion;

    public VideoLibraryService(AccessPolicy accessPolicy, VideoHostingProvider provider, NamedParameterJdbcTemplate jdbc,
                               TransactionTemplate transactions,
                               @Value("${yoga.content.max-devices:2}") int maxDevices,
                               @Value("${yoga.content.playback-token-minutes:120}") long tokenMinutes,
                               @Value("${yoga.content.heartbeat-seconds:60}") long heartbeatSeconds,
                               @Value("${yoga.content.waiver-version:2026-11}") String waiverVersion) {
        this.accessPolicy = accessPolicy;
        this.provider = provider;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.maxDevices = Math.max(1, maxDevices);
        this.tokenTtl = Duration.ofMinutes(Math.max(5, tokenMinutes));
        this.heartbeat = Duration.ofSeconds(Math.max(15, heartbeatSeconds));
        this.waiverVersion = waiverVersion;
    }

    private UserEntity student() {
        UserEntity user = accessPolicy.actor();
        if (!"STUDENT".equals(accessPolicy.role(user))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return user;
    }

    public VideoDto.Access access() {
        return access(student().getId());
    }

    private VideoDto.Access access(UUID me) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT m.end_date FROM yoga.memberships m WHERE m.id=yoga.video_pass(:me)) AS end_date,
                       yoga.video_pass(:me) IS NOT NULL AS has_pass,
                       EXISTS(SELECT 1 FROM yoga.video_waivers WHERE student_id=:me) AS waiver""", Map.of("me", me));
        Date end = (Date) row.get("end_date");
        return new VideoDto.Access(Boolean.TRUE.equals(row.get("has_pass")), end == null ? null : end.toLocalDate(),
                Boolean.TRUE.equals(row.get("waiver")), waiverVersion, maxDevices);
    }

    public VideoDto.Access acceptWaiver() {
        UUID me = student().getId();
        jdbc.update("INSERT INTO yoga.video_waivers(student_id,waiver_version) VALUES(:me,:v) ON CONFLICT (student_id) DO NOTHING",
                Map.of("me", me, "v", waiverVersion));
        return access(me);
    }

    public VideoDto.LibraryPage list(String level, String tag, Integer maxMinutes, String q, boolean inProgress, int page, int size) {
        UUID me = student().getId();
        boolean hasPass = access(me).hasPass();
        int safeSize = Math.min(Math.max(size, 1), 60);
        int safePage = Math.max(page, 0);
        var params = new MapSqlParameterSource().addValue("me", me).addValue("level", blankToNull(level)).addValue("tag", blankToNull(tag))
                .addValue("maxSeconds", maxMinutes == null || maxMinutes <= 0 ? null : maxMinutes * 60)
                .addValue("q", blankToNull(q) == null ? null : "%" + q.trim() + "%").addValue("inProgress", inProgress)
                .addValue("limit", safeSize).addValue("offset", safePage * safeSize);
        String where = """
                WHERE v.status='PUBLISHED' AND """ + COURSE_VISIBLE + """
                  AND (CAST(:level AS VARCHAR) IS NULL OR v.level=:level OR v.level='ALL_LEVELS')
                  AND (CAST(:tag AS VARCHAR) IS NULL OR EXISTS(SELECT 1 FROM yoga.video_lesson_tags x JOIN yoga.video_tags t ON t.id=x.tag_id
                       WHERE x.video_id=v.id AND t.code=:tag))
                  AND (CAST(:maxSeconds AS INT) IS NULL OR v.duration_seconds<=:maxSeconds)
                  AND (CAST(:q AS VARCHAR) IS NULL OR v.title ILIKE :q OR v.description ILIKE :q)
                  AND (NOT :inProgress OR (p.id IS NOT NULL AND p.completed_at IS NULL))
                """;
        long total = jdbc.queryForObject("SELECT count(*) FROM yoga.video_lessons v LEFT JOIN yoga.video_watch_progress p "
                + "ON p.video_id=v.id AND p.student_id=:me " + where, params, Long.class);
        String order = inProgress ? " ORDER BY p.updated_at DESC" : " ORDER BY v.published_at DESC, v.id";
        List<VideoDto.LibraryVideo> items = jdbc.query(SELECT_LIBRARY + where + order + " LIMIT :limit OFFSET :offset", params,
                (rs, i) -> map(rs, hasPass));
        return new VideoDto.LibraryPage(items, safePage, safeSize, total);
    }

    public VideoDto.VideoDetail detail(UUID id) {
        UUID me = student().getId();
        VideoDto.Access access = access(me);
        record Row(VideoDto.LibraryVideo video, String contraindications, boolean preg, boolean heart, boolean injury) {}
        Row row = jdbc.query(SELECT_LIBRARY + " WHERE v.id=:id AND v.status='PUBLISHED' AND " + COURSE_VISIBLE, Map.of("me", me, "id", id),
                (rs, i) -> new Row(map(rs, access.hasPass()), rs.getString("contraindications"), rs.getBoolean("not_for_pregnancy"),
                        rs.getBoolean("not_for_heart_bp"), rs.getBoolean("not_for_injuries")))
                .stream().findFirst().orElseThrow(() -> new BusinessException(ContentResultCodes.VIDEO_NOT_FOUND));
        return new VideoDto.VideoDetail(row.video(), row.contraindications(), warnings(me, row.preg(), row.heart(), row.injury()), access);
    }

    /** Opens a playback session on this device and returns a signed player URL. */
    public VideoDto.Playback startPlayback(UUID id, String deviceKey) {
        UserEntity me = student();
        UUID studentId = me.getId();
        VideoDto.VideoDetail detail = detail(id);
        if (detail.video().locked()) throw new BusinessException(ContentResultCodes.VIDEO_LOCKED);
        if (!detail.access().waiverAccepted()) throw new BusinessException(ContentResultCodes.WAIVER_REQUIRED);
        if (!provider.isConfigured()) throw new BusinessException(ContentResultCodes.NOT_CONFIGURED);
        String device = deviceKey.trim();
        record Opened(UUID sessionId, Instant expiresAt, String providerId) {}
        Opened opened = transactions.execute(tx -> {
            // Serialises the device check per student.
            jdbc.query("SELECT id FROM yoga.users WHERE id=:me FOR UPDATE", Map.of("me", studentId), rs -> null);
            jdbc.update("""
                    UPDATE yoga.video_playback_sessions SET ended_at=CURRENT_TIMESTAMP
                    WHERE student_id=:me AND device_key=:device AND ended_at IS NULL""", Map.of("me", studentId, "device", device));
            Integer open = jdbc.queryForObject("""
                    SELECT count(*) FROM yoga.video_playback_sessions
                    WHERE student_id=:me AND ended_at IS NULL AND expires_at>CURRENT_TIMESTAMP""", Map.of("me", studentId), Integer.class);
            if (open != null && open >= maxDevices) throw new BusinessException(ContentResultCodes.DEVICE_LIMIT);
            Instant expires = Instant.now().plus(heartbeat.multipliedBy(3));
            UUID session;
            try {
                session = jdbc.queryForObject("""
                        INSERT INTO yoga.video_playback_sessions(student_id,video_id,device_key,expires_at)
                        VALUES(:me,:video,:device,:expires) RETURNING id""",
                        new MapSqlParameterSource().addValue("me", studentId).addValue("video", id).addValue("device", device)
                                .addValue("expires", Timestamp.from(expires)), UUID.class);
            } catch (DataAccessException ex) {
                throw new BusinessException(ContentResultCodes.VIDEO_LOCKED);
            }
            String providerId = jdbc.queryForObject("SELECT provider_video_id FROM yoga.video_lessons WHERE id=:id", Map.of("id", id), String.class);
            return new Opened(session, expires, providerId);
        });
        VideoHostingProvider.SignedPlayback signed;
        try {
            signed = provider.playback(opened.providerId(), tokenTtl);
        } catch (ProviderException ex) {
            throw new BusinessException(ContentResultCodes.PROVIDER_ERROR);
        }
        VideoDto.LibraryVideo video = detail.video();
        int duration = video.durationSeconds() == null ? 0 : video.durationSeconds();
        int startAt = video.positionSeconds() > 0 && video.positionSeconds() < duration - 15 ? video.positionSeconds() : 0;
        return new VideoDto.Playback(opened.sessionId(), signed.embedUrl(), signed.expiresAt(), (int) heartbeat.toSeconds(), startAt,
                watermark(me), detail.warnings());
    }

    /** Player heartbeat: stores the position and the seconds actually watched since the last report. */
    public VideoDto.Progress reportProgress(UUID id, VideoDto.ProgressReq req) {
        UUID me = student().getId();
        return transactions.execute(tx -> {
            record Session(Instant lastSeen, Instant expires, Instant ended) {}
            Session session = jdbc.query("""
                    SELECT last_seen_at,expires_at,ended_at FROM yoga.video_playback_sessions
                    WHERE id=:sid AND student_id=:me AND video_id=:video FOR UPDATE""",
                    Map.of("sid", req.sessionId(), "me", me, "video", id),
                    (rs, i) -> new Session(instant(rs, "last_seen_at"), instant(rs, "expires_at"), instant(rs, "ended_at")))
                    .stream().findFirst().orElseThrow(() -> new BusinessException(ContentResultCodes.SESSION_EXPIRED));
            Instant now = Instant.now();
            if (session.ended() != null || !session.expires().isAfter(now)) throw new BusinessException(ContentResultCodes.SESSION_EXPIRED);
            long elapsed = Math.max(0, Duration.between(session.lastSeen(), now).toSeconds());
            int delta = (int) Math.min(Math.min(req.watchedSeconds(), elapsed + 5), MAX_REPORT_SECONDS);
            Instant expires = now.plus(heartbeat.multipliedBy(3));
            Map<String, Object> progress;
            try {
                progress = jdbc.queryForMap("""
                        INSERT INTO yoga.video_watch_progress(student_id,video_id,position_seconds,watched_seconds)
                        VALUES(:me,:video,:pos,:delta)
                        ON CONFLICT (student_id,video_id) DO UPDATE
                          SET position_seconds=EXCLUDED.position_seconds,
                              watched_seconds=yoga.video_watch_progress.watched_seconds+EXCLUDED.watched_seconds
                        RETURNING position_seconds,watched_seconds,completed_at IS NOT NULL AS completed""",
                        Map.of("me", me, "video", id, "pos", req.positionSeconds(), "delta", Math.max(0, delta)));
            } catch (DataAccessException ex) {
                // The pass ended or the video was unpublished while playing.
                throw new BusinessException(ContentResultCodes.VIDEO_LOCKED);
            }
            jdbc.update("UPDATE yoga.video_playback_sessions SET last_seen_at=CURRENT_TIMESTAMP,expires_at=:exp WHERE id=:sid",
                    new MapSqlParameterSource().addValue("exp", Timestamp.from(expires)).addValue("sid", req.sessionId()));
            return new VideoDto.Progress(((Number) progress.get("position_seconds")).intValue(),
                    ((Number) progress.get("watched_seconds")).intValue(), Boolean.TRUE.equals(progress.get("completed")), expires);
        });
    }

    /** The player closed: frees the device slot. */
    public void endSession(UUID sessionId) {
        UUID me = student().getId();
        jdbc.update("UPDATE yoga.video_playback_sessions SET ended_at=CURRENT_TIMESTAMP WHERE id=:sid AND student_id=:me AND ended_at IS NULL",
                Map.of("sid", sessionId, "me", me));
    }

    public List<VideoDto.StudentCollection> collections() {
        UUID me = student().getId();
        boolean hasPass = access(me).hasPass();
        List<UUID> ids = jdbc.queryForList("SELECT id FROM yoga.video_collections WHERE is_published ORDER BY created_at", Map.of(), UUID.class);
        List<VideoDto.StudentCollection> out = new ArrayList<>();
        for (UUID id : ids) out.add(collection(me, id, hasPass));
        return out;
    }

    public VideoDto.StudentCollection collection(UUID id) {
        UUID me = student().getId();
        return collection(me, id, access(me).hasPass());
    }

    private VideoDto.StudentCollection collection(UUID me, UUID id, boolean hasPass) {
        record Head(String title, String description, String level) {}
        Head head = jdbc.query("SELECT title,description,level FROM yoga.video_collections WHERE id=:id AND is_published", Map.of("id", id),
                (rs, i) -> new Head(rs.getString("title"), rs.getString("description"), rs.getString("level")))
                .stream().findFirst().orElseThrow(() -> new BusinessException(ContentResultCodes.COLLECTION_NOT_FOUND));
        List<VideoDto.LibraryVideo> items = jdbc.query(SELECT_LIBRARY + """
                JOIN yoga.video_collection_items ci ON ci.video_id=v.id AND ci.collection_id=:cid
                WHERE v.status='PUBLISHED' ORDER BY ci.position""", Map.of("me", me, "cid", id), (rs, i) -> map(rs, hasPass));
        int completed = (int) items.stream().filter(VideoDto.LibraryVideo::completed).count();
        return new VideoDto.StudentCollection(id, head.title(), head.description(), head.level(), items.size(), completed, items);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> warnings(UUID me, boolean notForPregnancy, boolean notForHeart, boolean notForInjury) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT has_injuries,has_heart_or_bp,is_pregnant FROM yoga.student_medical_profiles WHERE student_id=:me""", Map.of("me", me));
        if (rows.isEmpty()) return List.of();
        Map<String, Object> p = rows.get(0);
        List<String> out = new ArrayList<>();
        if (notForPregnancy && Boolean.TRUE.equals(p.get("is_pregnant"))) out.add("Bài này không phù hợp khi đang mang thai. Hãy hỏi ý kiến HLV hoặc bác sĩ trước khi tập.");
        if (notForHeart && Boolean.TRUE.equals(p.get("has_heart_or_bp"))) out.add("Hồ sơ của bạn có vấn đề tim mạch/huyết áp: bài này có động tác không khuyến nghị.");
        if (notForInjury && Boolean.TRUE.equals(p.get("has_injuries"))) out.add("Hồ sơ của bạn có chấn thương: bài này có thể gây áp lực lên vùng bị thương.");
        return out;
    }

    private static String watermark(UserEntity me) {
        String phone = me.getPhone() == null ? "" : me.getPhone().replaceAll("\\D", "");
        String tail = phone.length() >= 4 ? phone.substring(phone.length() - 4) : phone;
        return tail.isEmpty() ? me.getFullName() : me.getFullName() + " · " + tail;
    }

    private static VideoDto.LibraryVideo map(ResultSet rs, boolean hasPass) throws SQLException {
        boolean free = rs.getBoolean("is_free_preview");
        return new VideoDto.LibraryVideo(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"),
                rs.getString("level"), (Integer) rs.getObject("duration_seconds"), rs.getString("instructor_name"),
                rs.getString("thumbnail_url"), strings(rs.getArray("tags")), free, rs.getBoolean("locked"),
                rs.getInt("position_seconds"), rs.getInt("watched_seconds"), rs.getBoolean("completed"), instant(rs, "published_at"));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) out.add(String.valueOf(o));
        return out;
    }

}
