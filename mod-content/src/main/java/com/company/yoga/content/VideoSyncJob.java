package com.company.yoga.content;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls Bunny for videos still transcoding, in case a webhook was missed. */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoSyncJob {

    private final VideoAdminService videos;
    private final VideoHostingProvider provider;
    private final NamedParameterJdbcTemplate jdbc;

    @Scheduled(fixedDelayString = "${yoga.content.sync-delay-ms:60000}", initialDelayString = "${yoga.content.sync-delay-ms:60000}")
    public void sync() {
        if (!provider.isConfigured()) return;
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM yoga.video_lessons WHERE status='PROCESSING' AND provider_video_id IS NOT NULL
                ORDER BY updated_at LIMIT 20""", Map.of(), UUID.class);
        for (UUID id : ids) {
            try {
                videos.syncState(id);
            } catch (RuntimeException ex) {
                log.warn("Video {} status check failed: {}", id, ex.getMessage());
            }
        }
    }
}
