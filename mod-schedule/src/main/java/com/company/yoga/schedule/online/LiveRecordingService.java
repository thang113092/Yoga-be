package com.company.yoga.schedule.online;

import com.company.yoga.content.VideoHostingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Zoom cloud recordings of classes marked record_session become DRAFT videos (V20).
 * <ol>
 *   <li>{@link #receive}: the signed recording.completed webhook picks the main MP4 file, creates the draft video and
 *       queues a live_recordings row (inside the webhook transaction).</li>
 *   <li>{@link #importDue}: asks Bunny to fetch the file with the Zoom download token, then finds the new Bunny video by
 *       its unique title (the fetch API returns no id) and moves the draft to PROCESSING; the video sync job does the rest.</li>
 * </ol>
 * A manager reviews and publishes the video; course recordings are only for the course's students.
 */
@Service
@Slf4j
public class LiveRecordingService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final int MAX_ATTEMPTS = 6;

    private final NamedParameterJdbcTemplate jdbc;
    private final VideoHostingProvider videos;
    private final LiveMeetingProvider zoom;

    public LiveRecordingService(NamedParameterJdbcTemplate jdbc, VideoHostingProvider videos, LiveMeetingProvider zoom) {
        this.jdbc = jdbc;
        this.videos = videos;
        this.zoom = zoom;
    }

    /** Returns 1 when a recording was queued, 0 when the meeting is unknown, not recorded on purpose, or already queued. */
    public int receive(JsonNode root) {
        JsonNode object = root.path("payload").path("object");
        String meetingId = object.path("id").asText("");
        if (meetingId.isBlank()) return 0;
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.id,s.instructor_id,s.course_class_id,s.start_time,s.record_session,COALESCE(cc.name,ct.name) AS class_name,s.session_number
                FROM yoga.online_meetings m JOIN yoga.class_schedules s ON s.id=m.schedule_id
                JOIN yoga.class_types ct ON ct.id=s.class_type_id LEFT JOIN yoga.course_classes cc ON cc.id=s.course_class_id
                WHERE m.provider='ZOOM' AND m.provider_meeting_id=:m""", Map.of("m", meetingId));
        if (sessions.isEmpty() || !Boolean.TRUE.equals(sessions.get(0).get("record_session"))) return 0;
        Map<String, Object> s = sessions.get(0);
        JsonNode file = mainFile(object.path("recording_files"));
        if (file == null) return 0;
        String fileId = file.path("id").asText("");
        String url = file.path("download_url").asText("");
        if (fileId.isBlank() || !url.startsWith("https://")) return 0;
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM yoga.live_recordings WHERE provider='ZOOM' AND provider_recording_id=:f)",
                Map.of("f", fileId), Boolean.class))) return 0;

        Instant start = ((Timestamp) s.get("start_time")).toInstant();
        Object session = s.get("session_number");
        String title = s.get("class_name") + (session != null ? " – Buổi " + session : "") + " (ghi hình " + WHEN.format(start) + ")";
        UUID video = jdbc.queryForObject("""
                INSERT INTO yoga.video_lessons(title,description,level,instructor_id,created_by,source_schedule_id,course_class_id)
                VALUES(:title,'Bản ghi buổi học trực tuyến. Kiểm tra nội dung trước khi xuất bản.','ALL_LEVELS',:teacher,:teacher,:schedule,:course)
                RETURNING id""",
                new MapSqlParameterSource().addValue("title", title.length() > 200 ? title.substring(0, 200) : title)
                        .addValue("teacher", s.get("instructor_id")).addValue("schedule", s.get("id")).addValue("course", s.get("course_class_id")),
                UUID.class);
        String token = root.path("download_token").asText(null);
        jdbc.update("""
                INSERT INTO yoga.live_recordings(schedule_id,provider_recording_id,video_id,download_url,download_token,file_size,duration_seconds,import_title)
                VALUES(:schedule,:file,:video,:url,:token,:size,:duration,:importTitle)""",
                new MapSqlParameterSource().addValue("schedule", s.get("id")).addValue("file", fileId).addValue("video", video)
                        .addValue("url", url).addValue("token", token == null || token.isBlank() ? null : token)
                        .addValue("size", file.path("file_size").isNumber() ? file.path("file_size").asLong() : null)
                        .addValue("duration", object.path("duration").isNumber() ? object.path("duration").asInt() * 60 : null)
                        .addValue("importTitle", "rec-" + video));
        log.info("Queued Zoom recording {} of schedule {} as draft video {}", fileId, s.get("id"), video);
        return 1;
    }

    /** The speaker/screen MP4 (largest file), never audio-only or chat files. */
    static JsonNode mainFile(JsonNode files) {
        JsonNode best = null;
        for (JsonNode f : files) {
            boolean done = !f.has("status") || "completed".equalsIgnoreCase(f.path("status").asText());
            if (!"MP4".equalsIgnoreCase(f.path("file_type").asText()) || !done) continue;
            if (best == null || f.path("file_size").asLong(0) > best.path("file_size").asLong(0)) best = f;
        }
        return best;
    }

    @Scheduled(fixedDelayString = "${yoga.content.recording-delay-ms:60000}", initialDelayString = "${yoga.content.recording-delay-ms:60000}")
    public void importDue() {
        if (!videos.isConfigured()) return;
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id,status,video_id,download_url,download_token,import_title,attempts FROM yoga.live_recordings
                WHERE status IN ('RECEIVED','FETCH_REQUESTED') AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 10""", Map.of());
        for (Map<String, Object> r : due) {
            UUID id = (UUID) r.get("id");
            try {
                if ("RECEIVED".equals(r.get("status"))) requestFetch(id, r);
                else findImported(id, r);
            } catch (RuntimeException ex) {
                retryLater(id, ((Number) r.get("attempts")).intValue(), ex.getMessage());
            }
        }
    }

    private void requestFetch(UUID id, Map<String, Object> r) {
        String token = (String) r.get("download_token");
        if (token == null) token = zoom.recordingDownloadToken();
        videos.fetchFromUrl((String) r.get("download_url"), Map.of("Authorization", "Bearer " + token), (String) r.get("import_title"));
        jdbc.update("""
                UPDATE yoga.live_recordings SET status='FETCH_REQUESTED',attempts=attempts+1,last_error=NULL,fetch_requested_at=CURRENT_TIMESTAMP,
                  next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE id=:id AND status='RECEIVED'""", Map.of("id", id));
    }

    private void findImported(UUID id, Map<String, Object> r) {
        Optional<String> guid = videos.findVideoIdByTitle((String) r.get("import_title"));
        if (guid.isEmpty()) {
            // Bunny may still be queueing the fetch; ask again from scratch after a while.
            retryLater(id, ((Number) r.get("attempts")).intValue(), "Bunny has not created the video yet");
            return;
        }
        jdbc.update("UPDATE yoga.video_lessons SET provider_video_id=:p,status='PROCESSING' WHERE id=:v AND status='DRAFT' AND provider_video_id IS NULL",
                Map.of("p", guid.get(), "v", r.get("video_id")));
        jdbc.update("UPDATE yoga.live_recordings SET status='IMPORTED',last_error=NULL WHERE id=:id", Map.of("id", id));
        log.info("Recording {} imported into Bunny as {}", id, guid.get());
    }

    private void retryLater(UUID id, int attempts, String error) {
        String message = error == null ? "unknown" : (error.length() > 500 ? error.substring(0, 500) : error);
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("UPDATE yoga.live_recordings SET status='FAILED',last_error=:e WHERE id=:id AND status IN ('RECEIVED','FETCH_REQUESTED')",
                    Map.of("id", id, "e", message));
            log.warn("Recording {} import failed: {}", id, message);
            return;
        }
        // FETCH_REQUESTED that never showed up on Bunny goes back to RECEIVED so the fetch is requested again.
        jdbc.update("""
                UPDATE yoga.live_recordings SET last_error=:e,
                  status=CASE WHEN status='FETCH_REQUESTED' AND fetch_requested_at<CURRENT_TIMESTAMP-INTERVAL '30 minutes' THEN 'RECEIVED' ELSE status END,
                  next_attempt_at=CURRENT_TIMESTAMP+make_interval(mins=>LEAST(60,5*(attempts+1))) WHERE id=:id""",
                Map.of("id", id, "e", message));
    }
}
