package com.company.yoga.content;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.VideoHostingProvider.ProviderException;
import com.company.yoga.content.VideoHostingProvider.VideoState;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Content management: managers (super admin, branch manager) run the whole library; instructors create and upload
 * their own videos, which a manager publishes. Provider calls run outside database transactions.
 */
@Service
@Slf4j
public class VideoAdminService {

    static final Set<String> MANAGERS = Set.of("SUPER_ADMIN", "BRANCH_MANAGER");
    private static final Duration UPLOAD_TTL = Duration.ofHours(6);
    private static final Duration PREVIEW_TTL = Duration.ofHours(2);
    /** A video created on Bunny but never uploaded is given up after this long. */
    static final Duration UPLOAD_ABANDONED = Duration.ofHours(24);

    private static final String SELECT_VIDEO = """
            SELECT v.*, u.full_name AS instructor_name,
              (SELECT array_agg(t.tag_id) FROM yoga.video_lesson_tags t WHERE t.video_id=v.id) AS tag_ids,
              (SELECT count(*) FROM yoga.video_watch_progress p WHERE p.video_id=v.id) AS viewers,
              (SELECT lr.status FROM yoga.live_recordings lr WHERE lr.video_id=v.id) AS recording_status,
              (SELECT lr.last_error FROM yoga.live_recordings lr WHERE lr.video_id=v.id) AS recording_error,
              (SELECT cc.name FROM yoga.course_classes cc WHERE cc.id=v.course_class_id) AS course_name
            FROM yoga.video_lessons v LEFT JOIN yoga.users u ON u.id=v.instructor_id
            """;

    private final AccessPolicy accessPolicy;
    private final VideoHostingProvider provider;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public VideoAdminService(AccessPolicy accessPolicy, VideoHostingProvider provider, NamedParameterJdbcTemplate jdbc,
                             TransactionTemplate transactions) {
        this.accessPolicy = accessPolicy;
        this.provider = provider;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    record Actor(UUID id, String role) {
        boolean manager() {
            return MANAGERS.contains(role);
        }
    }

    /** Managers and instructors only. */
    Actor staff() {
        UserEntity user = accessPolicy.actor();
        String role = accessPolicy.role(user);
        if (!MANAGERS.contains(role) && !"INSTRUCTOR".equals(role)) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return new Actor(user.getId(), role);
    }

    Actor manager() {
        Actor actor = staff();
        if (!actor.manager()) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return actor;
    }

    // ---------------------------------------------------------------- videos

    public List<VideoDto.AdminVideo> list(String status, String q) {
        Actor actor = staff();
        var params = new MapSqlParameterSource().addValue("me", actor.id())
                .addValue("status", blankToNull(status)).addValue("q", blankToNull(q) == null ? null : "%" + q.trim() + "%");
        String sql = SELECT_VIDEO + """
                WHERE (CAST(:status AS VARCHAR) IS NULL OR v.status=:status)
                  AND (CAST(:q AS VARCHAR) IS NULL OR v.title ILIKE :q OR v.description ILIKE :q)
                """ + (actor.manager() ? "" : " AND (v.created_by=:me OR v.instructor_id=:me)") + " ORDER BY v.created_at DESC";
        return jdbc.query(sql, params, (rs, i) -> map(rs, actor));
    }

    public VideoDto.AdminVideo get(UUID id) {
        Actor actor = staff();
        VideoDto.AdminVideo video = load(id, actor);
        if (!actor.manager() && !owns(actor, video)) throw new BusinessException(ContentResultCodes.VIDEO_NOT_FOUND);
        return video;
    }

    public VideoDto.AdminVideo create(VideoDto.SaveVideoReq req) {
        Actor actor = staff();
        UUID instructor = actor.manager() ? req.instructorId() : actor.id();
        UUID id = transactions.execute(tx -> {
            UUID created = jdbc.queryForObject("""
                    INSERT INTO yoga.video_lessons(title,description,level,instructor_id,class_type_id,is_free_preview,
                      not_for_pregnancy,not_for_heart_bp,not_for_injuries,contraindications,created_by)
                    VALUES(:title,:description,:level,:instructor,:classType,:free,:preg,:heart,:injury,:contra,:me) RETURNING id""",
                    fields(req, instructor, actor.manager()).addValue("me", actor.id()), UUID.class);
            replaceTags(created, req.tagIds());
            return created;
        });
        return load(id, actor);
    }

    public VideoDto.AdminVideo update(UUID id, VideoDto.SaveVideoReq req) {
        Actor actor = staff();
        VideoDto.AdminVideo current = load(id, actor);
        if (!actor.manager() && (!owns(actor, current) || "PUBLISHED".equals(current.status()))) {
            throw new BusinessException(ContentResultCodes.VIDEO_NOT_EDITABLE);
        }
        UUID instructor = actor.manager() ? req.instructorId() : current.instructorId();
        boolean free = actor.manager() ? Boolean.TRUE.equals(req.isFreePreview()) : current.isFreePreview();
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE yoga.video_lessons SET title=:title,description=:description,level=:level,instructor_id=:instructor,
                      class_type_id=:classType,is_free_preview=:free,not_for_pregnancy=:preg,not_for_heart_bp=:heart,
                      not_for_injuries=:injury,contraindications=:contra
                    WHERE id=:id""", fields(req, instructor, true).addValue("free", free).addValue("id", id));
            replaceTags(id, req.tagIds());
        });
        return load(id, actor);
    }

    /** Creates the video on Bunny on first use and returns TUS upload credentials. */
    public VideoDto.UploadSession uploadSession(UUID id) {
        Actor actor = staff();
        VideoDto.AdminVideo video = load(id, actor);
        if (!actor.manager() && !owns(actor, video)) throw new BusinessException(ContentResultCodes.VIDEO_NOT_EDITABLE);
        if (!Set.of("DRAFT", "FAILED", "PROCESSING").contains(video.status())) throw new BusinessException(ContentResultCodes.VIDEO_INVALID_STATUS);
        requireConfigured();
        String providerId = providerId(id);
        if (providerId == null) {
            String created = withProvider(() -> provider.createVideo(video.title()));
            int claimed = jdbc.update("UPDATE yoga.video_lessons SET provider_video_id=:p WHERE id=:id AND provider_video_id IS NULL",
                    Map.of("p", created, "id", id));
            if (claimed == 0) {
                // Another request created it first: keep theirs and remove ours.
                safeDelete(created);
            }
            providerId = providerId(id);
        }
        jdbc.update("UPDATE yoga.video_lessons SET status='PROCESSING' WHERE id=:id AND status IN ('DRAFT','FAILED')", Map.of("id", id));
        String pid = providerId;
        VideoHostingProvider.UploadTicket ticket = withProvider(() -> provider.uploadTicket(pid, UPLOAD_TTL));
        return new VideoDto.UploadSession(id, ticket.endpoint(), ticket.libraryId(), ticket.providerVideoId(), ticket.signature(),
                ticket.expiresEpochSeconds());
    }

    /** Asks Bunny for the transcoding state now. */
    public VideoDto.AdminVideo refresh(UUID id) {
        Actor actor = staff();
        VideoDto.AdminVideo video = load(id, actor);
        if (!actor.manager() && !owns(actor, video)) throw new BusinessException(ContentResultCodes.VIDEO_NOT_FOUND);
        requireConfigured();
        syncState(id);
        return load(id, actor);
    }

    /** Pulls the state of a PROCESSING video from the host and stores it. Returns false when nothing changed. */
    public boolean syncState(UUID id) {
        String providerId = providerId(id);
        if (providerId == null) return false;
        VideoState state = withProvider(() -> provider.state(providerId));
        return applyState(id, state);
    }

    boolean applyState(UUID id, VideoState state) {
        var params = new MapSqlParameterSource().addValue("id", id).addValue("thumb", state.thumbnailUrl())
                .addValue("dur", state.durationSeconds()).addValue("err", state.error())
                .addValue("abandoned", Timestamp.from(Instant.now().minus(UPLOAD_ABANDONED)));
        return switch (state.phase()) {
            case READY -> jdbc.update("""
                    UPDATE yoga.video_lessons SET status='READY',duration_seconds=COALESCE(duration_seconds,:dur),
                      thumbnail_url=COALESCE(:thumb,thumbnail_url)
                    WHERE id=:id AND status='PROCESSING'""", params) > 0;
            case FAILED -> jdbc.update("UPDATE yoga.video_lessons SET status='FAILED',processing_error=:err WHERE id=:id AND status='PROCESSING'",
                    params) > 0;
            case WAITING_FOR_UPLOAD -> jdbc.update("""
                    UPDATE yoga.video_lessons SET status='FAILED',processing_error='Chưa tải file video lên trong 24 giờ'
                    WHERE id=:id AND status='PROCESSING' AND updated_at<:abandoned""", params) > 0;
            case PROCESSING -> false;
        };
    }

    public VideoDto.AdminVideo publish(UUID id) {
        Actor actor = manager();
        if (jdbc.update("UPDATE yoga.video_lessons SET status='PUBLISHED' WHERE id=:id AND status='READY'", Map.of("id", id)) == 0) {
            VideoDto.AdminVideo video = load(id, actor);
            if (!"PUBLISHED".equals(video.status())) throw new BusinessException(ContentResultCodes.VIDEO_NOT_READY);
        }
        return load(id, actor);
    }

    public VideoDto.AdminVideo unpublish(UUID id) {
        Actor actor = manager();
        if (jdbc.update("UPDATE yoga.video_lessons SET status='READY' WHERE id=:id AND status='PUBLISHED'", Map.of("id", id)) == 0) {
            load(id, actor);
            throw new BusinessException(ContentResultCodes.VIDEO_INVALID_STATUS);
        }
        return load(id, actor);
    }

    public VideoDto.AdminVideo archive(UUID id) {
        Actor actor = manager();
        if (jdbc.update("UPDATE yoga.video_lessons SET status='ARCHIVED' WHERE id=:id AND status IN ('DRAFT','FAILED','READY','PUBLISHED')",
                Map.of("id", id)) == 0) {
            load(id, actor);
            throw new BusinessException(ContentResultCodes.VIDEO_INVALID_STATUS);
        }
        return load(id, actor);
    }

    public VideoDto.AdminVideo restore(UUID id) {
        Actor actor = manager();
        if (jdbc.update("""
                UPDATE yoga.video_lessons SET status=CASE WHEN provider_video_id IS NULL THEN 'DRAFT' ELSE 'READY' END
                WHERE id=:id AND status='ARCHIVED' AND (provider_video_id IS NULL OR duration_seconds IS NOT NULL)""", Map.of("id", id)) == 0) {
            load(id, actor);
            throw new BusinessException(ContentResultCodes.VIDEO_INVALID_STATUS);
        }
        return load(id, actor);
    }

    /** Deletes an unpublished video nobody watched, then the file on Bunny. */
    public void delete(UUID id) {
        Actor actor = staff();
        VideoDto.AdminVideo video = load(id, actor);
        if (!actor.manager() && !owns(actor, video)) throw new BusinessException(ContentResultCodes.VIDEO_NOT_EDITABLE);
        if (!Set.of("DRAFT", "FAILED").contains(video.status()) || video.viewers() > 0) throw new BusinessException(ContentResultCodes.VIDEO_IN_USE);
        String providerId = providerId(id);
        try {
            jdbc.update("DELETE FROM yoga.video_lessons WHERE id=:id", Map.of("id", id));
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ContentResultCodes.VIDEO_IN_USE);
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new BusinessException(ContentResultCodes.VIDEO_IN_USE);
        }
        if (providerId != null && provider.isConfigured()) safeDelete(providerId);
    }

    /** Staff preview without a playback session or progress. */
    public VideoDto.Preview preview(UUID id) {
        Actor actor = staff();
        VideoDto.AdminVideo video = load(id, actor);
        if (!actor.manager() && !owns(actor, video)) throw new BusinessException(ContentResultCodes.VIDEO_NOT_FOUND);
        if (video.durationSeconds() == null) throw new BusinessException(ContentResultCodes.VIDEO_NOT_READY);
        requireConfigured();
        String providerId = providerId(id);
        VideoHostingProvider.SignedPlayback playback = withProvider(() -> provider.playback(providerId, PREVIEW_TTL));
        return new VideoDto.Preview(playback.embedUrl(), playback.expiresAt());
    }

    // ---------------------------------------------------------------- tags

    public List<VideoDto.Tag> tags() {
        accessPolicy.actor();
        return jdbc.query("""
                SELECT t.id,t.code,t.name,(SELECT count(*) FROM yoga.video_lesson_tags x WHERE x.tag_id=t.id) AS videos
                FROM yoga.video_tags t ORDER BY t.name""", Map.of(),
                (rs, i) -> new VideoDto.Tag(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getLong("videos")));
    }

    public VideoDto.Tag createTag(VideoDto.SaveTagReq req) {
        manager();
        try {
            UUID id = jdbc.queryForObject("INSERT INTO yoga.video_tags(code,name) VALUES(:code,:name) RETURNING id",
                    Map.of("code", req.code().trim().toUpperCase(Locale.ROOT), "name", req.name().trim()), UUID.class);
            return new VideoDto.Tag(id, req.code().trim().toUpperCase(Locale.ROOT), req.name().trim(), 0);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ContentResultCodes.TAG_CODE_EXISTS);
        }
    }

    public void deleteTag(UUID id) {
        manager();
        jdbc.update("DELETE FROM yoga.video_tags WHERE id=:id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- collections

    public List<VideoDto.AdminCollection> collections() {
        staff();
        List<VideoDto.AdminCollection> list = jdbc.query("SELECT * FROM yoga.video_collections ORDER BY created_at DESC", Map.of(),
                (rs, i) -> new VideoDto.AdminCollection(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"),
                        rs.getString("level"), rs.getBoolean("is_published"), List.of(), instant(rs, "updated_at")));
        return list.stream().map(c -> new VideoDto.AdminCollection(c.id(), c.title(), c.description(), c.level(), c.isPublished(),
                items(c.id()), c.updatedAt())).toList();
    }

    public VideoDto.AdminCollection createCollection(VideoDto.SaveCollectionReq req) {
        Actor actor = manager();
        UUID id = transactions.execute(tx -> {
            UUID created = jdbc.queryForObject("""
                    INSERT INTO yoga.video_collections(title,description,level,is_published,created_by)
                    VALUES(:title,:description,:level,:published,:me) RETURNING id""",
                    collectionFields(req).addValue("me", actor.id()), UUID.class);
            replaceItems(created, req.videoIds());
            return created;
        });
        return collection(id);
    }

    public VideoDto.AdminCollection updateCollection(UUID id, VideoDto.SaveCollectionReq req) {
        manager();
        transactions.executeWithoutResult(tx -> {
            if (jdbc.update("UPDATE yoga.video_collections SET title=:title,description=:description,level=:level,is_published=:published WHERE id=:id",
                    collectionFields(req).addValue("id", id)) == 0) {
                throw new BusinessException(ContentResultCodes.COLLECTION_NOT_FOUND);
            }
            replaceItems(id, req.videoIds());
        });
        return collection(id);
    }

    public void deleteCollection(UUID id) {
        manager();
        jdbc.update("DELETE FROM yoga.video_collections WHERE id=:id", Map.of("id", id));
    }

    private VideoDto.AdminCollection collection(UUID id) {
        return jdbc.query("SELECT * FROM yoga.video_collections WHERE id=:id", Map.of("id", id),
                (rs, i) -> new VideoDto.AdminCollection(id, rs.getString("title"), rs.getString("description"), rs.getString("level"),
                        rs.getBoolean("is_published"), items(id), instant(rs, "updated_at")))
                .stream().findFirst().orElseThrow(() -> new BusinessException(ContentResultCodes.COLLECTION_NOT_FOUND));
    }

    private List<VideoDto.CollectionItem> items(UUID collectionId) {
        return jdbc.query("""
                SELECT i.video_id,v.title,i.position,v.duration_seconds,v.status FROM yoga.video_collection_items i
                JOIN yoga.video_lessons v ON v.id=i.video_id WHERE i.collection_id=:id ORDER BY i.position""", Map.of("id", collectionId),
                (rs, i) -> new VideoDto.CollectionItem(rs.getObject("video_id", UUID.class), rs.getString("title"), rs.getInt("position"),
                        (Integer) rs.getObject("duration_seconds"), rs.getString("status")));
    }

    private void replaceItems(UUID collectionId, List<UUID> videoIds) {
        List<UUID> ids = videoIds == null ? List.of() : videoIds;
        if (new LinkedHashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ContentResultCodes.COLLECTION_INVALID_VIDEO);
        }
        jdbc.update("DELETE FROM yoga.video_collection_items WHERE collection_id=:id", Map.of("id", collectionId));
        int position = 1;
        for (UUID videoId : ids) {
            try {
                jdbc.update("INSERT INTO yoga.video_collection_items(collection_id,video_id,position) VALUES(:c,:v,:p)",
                        Map.of("c", collectionId, "v", videoId, "p", position++));
            } catch (org.springframework.dao.DataAccessException ex) {
                throw new BusinessException(ContentResultCodes.COLLECTION_INVALID_VIDEO);
            }
        }
    }

    private static MapSqlParameterSource collectionFields(VideoDto.SaveCollectionReq req) {
        return new MapSqlParameterSource().addValue("title", req.title().trim()).addValue("description", blankToNull(req.description()))
                .addValue("level", req.level() == null ? "ALL_LEVELS" : req.level()).addValue("published", Boolean.TRUE.equals(req.isPublished()));
    }

    // ---------------------------------------------------------------- helpers

    private VideoDto.AdminVideo load(UUID id, Actor actor) {
        return jdbc.query(SELECT_VIDEO + " WHERE v.id=:id", Map.of("id", id), (rs, i) -> map(rs, actor)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ContentResultCodes.VIDEO_NOT_FOUND));
    }

    private String providerId(UUID id) {
        // The column is NULL until the first upload: findFirst() would throw on a null element.
        List<String> rows = jdbc.queryForList("SELECT provider_video_id FROM yoga.video_lessons WHERE id=:id", Map.of("id", id), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean owns(Actor actor, VideoDto.AdminVideo video) {
        return actor.id().equals(video.createdBy()) || actor.id().equals(video.instructorId());
    }

    private void replaceTags(UUID videoId, List<UUID> tagIds) {
        jdbc.update("DELETE FROM yoga.video_lesson_tags WHERE video_id=:id", Map.of("id", videoId));
        for (UUID tag : new LinkedHashSet<>(tagIds == null ? List.<UUID>of() : tagIds)) {
            if (tag == null) continue;
            try {
                jdbc.update("INSERT INTO yoga.video_lesson_tags(video_id,tag_id) VALUES(:v,:t)", Map.of("v", videoId, "t", tag));
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessException(CommonErrorCode.BAD_REQUEST);
            }
        }
    }

    private static MapSqlParameterSource fields(VideoDto.SaveVideoReq req, UUID instructor, boolean mayMarkFree) {
        return new MapSqlParameterSource()
                .addValue("title", req.title().trim()).addValue("description", blankToNull(req.description()))
                .addValue("level", req.level() == null ? "ALL_LEVELS" : req.level()).addValue("instructor", instructor)
                .addValue("classType", req.classTypeId()).addValue("free", mayMarkFree && Boolean.TRUE.equals(req.isFreePreview()))
                .addValue("preg", Boolean.TRUE.equals(req.notForPregnancy())).addValue("heart", Boolean.TRUE.equals(req.notForHeartBp()))
                .addValue("injury", Boolean.TRUE.equals(req.notForInjuries())).addValue("contra", blankToNull(req.contraindications()));
    }

    private VideoDto.AdminVideo map(ResultSet rs, Actor actor) throws SQLException {
        UUID createdBy = rs.getObject("created_by", UUID.class);
        UUID instructor = rs.getObject("instructor_id", UUID.class);
        String status = rs.getString("status");
        boolean canEdit = actor.manager() || ((actor.id().equals(createdBy) || actor.id().equals(instructor)) && !"PUBLISHED".equals(status));
        return new VideoDto.AdminVideo(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"), rs.getString("level"),
                (Integer) rs.getObject("duration_seconds"), instructor, rs.getString("instructor_name"), rs.getObject("class_type_id", UUID.class),
                status, rs.getString("processing_error"), rs.getBoolean("is_free_preview"), rs.getBoolean("not_for_pregnancy"),
                rs.getBoolean("not_for_heart_bp"), rs.getBoolean("not_for_injuries"), rs.getString("contraindications"),
                rs.getString("thumbnail_url"), uuids(rs.getArray("tag_ids")), createdBy, instant(rs, "published_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("viewers"), canEdit,
                rs.getObject("source_schedule_id", UUID.class), rs.getObject("course_class_id", UUID.class), rs.getString("course_name"),
                rs.getString("recording_status"), rs.getString("recording_error"));
    }

    static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        List<UUID> out = new ArrayList<>(values.length);
        Arrays.stream(values).forEach(v -> out.add(v instanceof UUID u ? u : UUID.fromString(String.valueOf(v))));
        return out;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireConfigured() {
        if (!provider.isConfigured()) throw new BusinessException(ContentResultCodes.NOT_CONFIGURED);
    }

    private <T> T withProvider(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderException ex) {
            log.warn("Video host call failed: {}", ex.getMessage());
            throw new BusinessException(ContentResultCodes.PROVIDER_ERROR);
        }
    }

    private void safeDelete(String providerId) {
        try {
            provider.delete(providerId);
        } catch (ProviderException ex) {
            log.warn("Could not delete video {} on the host: {}", providerId, ex.getMessage());
        }
    }
}
