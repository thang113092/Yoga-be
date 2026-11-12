package com.company.yoga.schedule.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Zoom callbacks: only the signature is trusted; each event is stored once before it is applied. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZoomWebhookService {

    public sealed interface Outcome permits Rejected, Accepted, Challenge {}
    public record Rejected() implements Outcome {}
    public record Accepted() implements Outcome {}
    public record Challenge(String plainToken, String encryptedToken) implements Outcome {}

    private final ZoomWebhookVerifier verifier;
    private final ObjectMapper mapper;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LiveRecordingService recordings;

    public Outcome handle(String timestamp, String signature, String body) {
        if (!verifier.verify(timestamp, signature, body)) return new Rejected();
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception ex) {
            return new Rejected();
        }
        String event = root.path("event").asText("");
        if ("endpoint.url_validation".equals(event)) {
            String plain = root.path("payload").path("plainToken").asText("");
            return plain.isBlank() ? new Rejected() : new Challenge(plain, verifier.encryptToken(plain));
        }
        String meetingId = root.path("payload").path("object").path("id").asText("");
        String reference = event + ":" + meetingId + ":" + root.path("event_ts").asText(timestamp);
        Optional<UUID> stored = store(event, reference, body);
        if (stored.isEmpty()) return new Accepted();   // redelivery of an event already handled
        UUID eventId = stored.get();
        try {
            String result = transactions.execute(tx -> ("recording.completed".equals(event) ? recordings.receive(root) : apply(event, meetingId)) > 0
                    ? "PROCESSED" : "IGNORED");
            finish(eventId, result, null);
        } catch (RuntimeException ex) {
            log.warn("Zoom event {} could not be applied: {}", reference, ex.getMessage());
            finish(eventId, "FAILED", ex.getMessage());
        }
        return new Accepted();
    }

    private int apply(String event, String meetingId) {
        if (meetingId.isBlank()) return 0;
        return switch (event) {
            case "meeting.started" -> jdbc.update("""
                    UPDATE yoga.online_meetings SET status='STARTED'
                    WHERE provider='ZOOM' AND provider_meeting_id=:id AND status IN ('READY','UPDATE_PENDING','ENDED')""", Map.of("id", meetingId));
            case "meeting.ended" -> jdbc.update("""
                    UPDATE yoga.online_meetings SET status='ENDED'
                    WHERE provider='ZOOM' AND provider_meeting_id=:id AND status IN ('READY','UPDATE_PENDING','STARTED')""", Map.of("id", meetingId));
            default -> 0;
        };
    }

    private Optional<UUID> store(String event, String reference, String body) {
        List<UUID> ids = jdbc.queryForList("""
                INSERT INTO yoga.integration_events(provider,event_reference,event_type,payload)
                VALUES('ZOOM',:ref,:type,CAST(:payload AS jsonb))
                ON CONFLICT (provider,event_reference) DO NOTHING RETURNING id""",
                new MapSqlParameterSource().addValue("ref", truncate(reference, 200)).addValue("type", truncate(event.isBlank() ? "unknown" : event, 100))
                        .addValue("payload", body), UUID.class);
        return ids.stream().findFirst();
    }

    private void finish(UUID id, String result, String error) {
        jdbc.update("UPDATE yoga.integration_events SET processed_at=CURRENT_TIMESTAMP,result=:r,error_message=:e WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("r", result).addValue("e", error));
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
