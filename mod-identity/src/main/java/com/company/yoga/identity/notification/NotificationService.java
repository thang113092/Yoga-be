package com.company.yoga.identity.notification;

import com.company.yoga.identity.account.service.AccessPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** In-app notifications of the signed-in user, and the job that creates renewal reminders (V17). */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public record Item(UUID id, String type, String title, String body, String link, Instant createdAt, boolean read) {}

    public record Inbox(List<Item> items, long unread) {}

    public Inbox mine(int limit) {
        UUID me = accessPolicy.actor().getId();
        List<Item> items = jdbc.query("""
                SELECT id,type,title,body,link,created_at,read_at IS NOT NULL AS read FROM yoga.notifications
                WHERE user_id=:me ORDER BY created_at DESC LIMIT :limit""",
                new MapSqlParameterSource().addValue("me", me).addValue("limit", Math.min(Math.max(limit, 1), 100)),
                (rs, i) -> new Item(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("title"), rs.getString("body"),
                        rs.getString("link"), toInstant(rs.getTimestamp("created_at")), rs.getBoolean("read")));
        Long unread = jdbc.queryForObject("SELECT count(*) FROM yoga.notifications WHERE user_id=:me AND read_at IS NULL",
                Map.of("me", me), Long.class);
        return new Inbox(items, unread == null ? 0 : unread);
    }

    public void markRead(UUID id) {
        jdbc.update("UPDATE yoga.notifications SET read_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:me AND read_at IS NULL",
                Map.of("id", id, "me", accessPolicy.actor().getId()));
    }

    public void markAllRead() {
        jdbc.update("UPDATE yoga.notifications SET read_at=CURRENT_TIMESTAMP WHERE user_id=:me AND read_at IS NULL",
                Map.of("me", accessPolicy.actor().getId()));
    }

    /** Idempotent (dedupe keys in the database), so running hourly is safe and survives the server sleeping. */
    @Scheduled(fixedDelayString = "${yoga.notifications.delay-ms:3600000}", initialDelayString = "${yoga.notifications.initial-delay-ms:60000}")
    public void generateReminders() {
        try {
            Integer added = jdbc.getJdbcTemplate().queryForObject("SELECT yoga.generate_notifications()", Integer.class);
            if (added != null && added > 0) log.info("Created {} reminder notifications", added);
        } catch (RuntimeException ex) {
            log.warn("Reminder notifications could not be generated: {}", ex.getMessage());
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
