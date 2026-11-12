package com.company.yoga.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bunny Stream status callbacks. The payload is only a hint: the caller must know the shared secret in the URL,
 * and the video state is then read back from the Bunny API, so a forged body cannot publish anything.
 */
@Service
@Slf4j
public class BunnyWebhookService {

    private final VideoAdminService videos;
    private final ObjectMapper mapper;
    private final NamedParameterJdbcTemplate jdbc;
    private final byte[] secret;

    public BunnyWebhookService(VideoAdminService videos, ObjectMapper mapper, NamedParameterJdbcTemplate jdbc,
                               @Value("${yoga.content.bunny.webhook-secret:}") String secret) {
        this.videos = videos;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.secret = secret == null ? new byte[0] : secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** False when the secret is wrong or missing. */
    public boolean handle(String token, String body) {
        if (secret.length == 0 || token == null || !MessageDigest.isEqual(secret, token.getBytes(StandardCharsets.UTF_8))) return false;
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception ex) {
            return true;   // acknowledged; nothing to do
        }
        String guid = root.path("VideoGuid").asText("");
        String status = root.path("Status").asText("");
        // Identical redeliveries are stored once; the state is re-read from Bunny every time anyway.
        Optional<UUID> event = store(guid + ":" + status + ":" + BunnyStreamProvider.sha256Hex(body).substring(0, 16), body);
        if (guid.isBlank()) {
            event.ifPresent(id -> finish(id, "IGNORED", null));
            return true;
        }
        List<UUID> ids = jdbc.queryForList("SELECT id FROM yoga.video_lessons WHERE provider='BUNNY' AND provider_video_id=:p",
                Map.of("p", guid), UUID.class);
        String result = "IGNORED";
        String error = null;
        try {
            for (UUID id : ids) if (videos.syncState(id)) result = "PROCESSED";
        } catch (RuntimeException ex) {
            result = "FAILED";
            error = ex.getMessage();
            log.warn("Bunny event for {} could not be applied: {}", guid, ex.getMessage());
        }
        String finalResult = result;
        String finalError = error;
        event.ifPresent(id -> finish(id, finalResult, finalError));
        return true;
    }

    private Optional<UUID> store(String reference, String body) {
        String payload;
        try {
            payload = mapper.readTree(body).toString();
        } catch (Exception ex) {
            payload = "{}";
        }
        List<UUID> ids = jdbc.queryForList("""
                INSERT INTO yoga.integration_events(provider,event_reference,event_type,payload)
                VALUES('BUNNY',:ref,'video.status',CAST(:payload AS jsonb))
                ON CONFLICT (provider,event_reference) DO NOTHING RETURNING id""",
                new MapSqlParameterSource().addValue("ref", reference.length() > 200 ? reference.substring(0, 200) : reference)
                        .addValue("payload", payload), UUID.class);
        return ids.stream().findFirst();
    }

    private void finish(UUID id, String result, String error) {
        jdbc.update("UPDATE yoga.integration_events SET processed_at=CURRENT_TIMESTAMP,result=:r,error_message=:e WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("r", result).addValue("e", error));
    }
}
