package com.company.yoga.schedule.online;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Zoom Meeting API with a Server-to-Server OAuth app.
 * Meetings use registration so that every booking gets its own join link.
 */
@Component
@Slf4j
public class ZoomMeetingProvider implements LiveMeetingProvider {

    static final String PROVIDER = "ZOOM";
    /** Stored as the registrant id when everyone uses the meeting's shared link. */
    static final String SHARED_LINK = "SHARED";

    private final RestClient api;
    private final RestClient oauth;
    private final String accountId;
    private final String basicAuth;
    private final String defaultHost;
    private final boolean configured;
    /**
     * true: one registrant (personal, revocable link) per booking; needs a paid (Pro+) host.
     * false: everyone gets the meeting's shared link (works on Zoom Basic; for testing only).
     */
    private final boolean useRegistration;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public ZoomMeetingProvider(@Value("${yoga.online.zoom.api-base-url:https://api.zoom.us/v2}") String apiBaseUrl,
                               @Value("${yoga.online.zoom.oauth-url:https://zoom.us/oauth/token}") String oauthUrl,
                               @Value("${yoga.online.zoom.account-id:}") String accountId,
                               @Value("${yoga.online.zoom.client-id:}") String clientId,
                               @Value("${yoga.online.zoom.client-secret:}") String clientSecret,
                               @Value("${yoga.online.zoom.default-host:me}") String defaultHost,
                               @Value("${yoga.online.zoom.use-registration:true}") boolean useRegistration) {
        this(client(apiBaseUrl), client(oauthUrl), accountId, clientId, clientSecret, defaultHost, useRegistration);
    }

    ZoomMeetingProvider(RestClient api, RestClient oauth, String accountId, String clientId, String clientSecret, String defaultHost) {
        this(api, oauth, accountId, clientId, clientSecret, defaultHost, true);
    }

    ZoomMeetingProvider(RestClient api, RestClient oauth, String accountId, String clientId, String clientSecret, String defaultHost,
                        boolean useRegistration) {
        this.useRegistration = useRegistration;
        this.api = api;
        this.oauth = oauth;
        this.accountId = accountId;
        this.basicAuth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        this.defaultHost = defaultHost == null || defaultHost.isBlank() ? "me" : defaultHost.trim();
        this.configured = !accountId.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }

    private static RestClient client(String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public boolean personalLinks() {
        return useRegistration;
    }

    @Override
    public String createMeeting(MeetingSpec spec) {
        String host = spec.hostAccount() == null || spec.hostAccount().isBlank() ? defaultHost : spec.hostAccount().trim();
        Map<String, Object> body = meetingBody(spec);
        Map<String, Object> settings = new LinkedHashMap<>();
        if (useRegistration) {
            settings.put("approval_type", 0);      // registration is approved automatically
            settings.put("registration_type", 1);
            settings.put("registrants_email_notification", false);
            settings.put("registrants_confirmation_email", false);
        }
        settings.put("join_before_host", false);
        settings.put("waiting_room", false);
        settings.put("mute_upon_entry", true);
        settings.put("participant_video", false);
        settings.put("host_video", true);
        settings.put("auto_recording", "none");
        settings.put("meeting_authentication", false);
        body.put("settings", settings);
        JsonNode created = call(() -> api.post().uri("/users/{host}/meetings", host).headers(h -> h.setBearerAuth(token()))
                .body(body).retrieve().body(JsonNode.class));
        if (created == null || !created.hasNonNull("id")) throw new ProviderException("Zoom response without meeting id", false);
        return created.get("id").asText();
    }

    @Override
    public void updateMeeting(String meetingId, MeetingSpec spec) {
        call(() -> api.patch().uri("/meetings/{id}", meetingId).headers(h -> h.setBearerAuth(token()))
                .body(meetingBody(spec)).retrieve().toBodilessEntity());
    }

    @Override
    public void deleteMeeting(String meetingId) {
        ignoreMissing(() -> api.delete().uri("/meetings/{id}?schedule_for_reminder=false", meetingId)
                .headers(h -> h.setBearerAuth(token())).retrieve().toBodilessEntity());
    }

    @Override
    public Registrant addRegistrant(String meetingId, RegistrantSpec spec) {
        if (!useRegistration) {
            // Zoom Basic cannot register attendees: hand out the meeting's own link (it carries the passcode).
            JsonNode meeting = call(() -> api.get().uri("/meetings/{id}", meetingId).headers(h -> h.setBearerAuth(token()))
                    .retrieve().body(JsonNode.class));
            if (meeting == null || !meeting.hasNonNull("join_url")) throw new ProviderException("Zoom response without join link", false);
            return new Registrant(SHARED_LINK, meeting.get("join_url").asText());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", spec.email());
        body.put("first_name", spec.firstName());
        if (spec.lastName() != null && !spec.lastName().isBlank()) body.put("last_name", spec.lastName());
        JsonNode created = call(() -> api.post().uri("/meetings/{id}/registrants", meetingId).headers(h -> h.setBearerAuth(token()))
                .body(body).retrieve().body(JsonNode.class));
        if (created == null || !created.hasNonNull("registrant_id") || !created.hasNonNull("join_url")) {
            throw new ProviderException("Zoom response without registrant link", false);
        }
        return new Registrant(created.get("registrant_id").asText(), created.get("join_url").asText());
    }

    @Override
    public void removeRegistrant(String meetingId, String registrantId) {
        // A shared link cannot be revoked for one person; decided by the stored id, so switching modes stays safe.
        if (SHARED_LINK.equals(registrantId)) return;
        ignoreMissing(() -> api.delete().uri("/meetings/{id}/registrants/{registrant}", meetingId, registrantId)
                .headers(h -> h.setBearerAuth(token())).retrieve().toBodilessEntity());
    }

    @Override
    public String hostStartUrl(String meetingId) {
        JsonNode meeting = call(() -> api.get().uri("/meetings/{id}", meetingId).headers(h -> h.setBearerAuth(token()))
                .retrieve().body(JsonNode.class));
        if (meeting == null || !meeting.hasNonNull("start_url")) throw new ProviderException("Zoom response without host link", false);
        return meeting.get("start_url").asText();
    }

    private static Map<String, Object> meetingBody(MeetingSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", spec.topic().length() > 200 ? spec.topic().substring(0, 200) : spec.topic());
        body.put("type", 2);
        body.put("start_time", spec.start().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("duration", Math.max(1, spec.durationMinutes()));
        body.put("timezone", "Asia/Ho_Chi_Minh");
        return body;
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) return token;
        JsonNode response = call(() -> oauth.post().uri(b -> b.queryParam("grant_type", "account_credentials").queryParam("account_id", accountId).build())
                .header("Authorization", "Basic " + basicAuth).retrieve().body(JsonNode.class));
        if (response == null || !response.hasNonNull("access_token")) throw new ProviderException("Zoom OAuth without access token", true);
        token = response.get("access_token").asText();
        tokenExpiresAt = Instant.now().plusSeconds(response.path("expires_in").asLong(3600));
        return token;
    }

    private void ignoreMissing(Runnable request) {
        try {
            call(() -> {
                request.run();
                return null;
            });
        } catch (MissingResource ignored) {
            // Already gone on Zoom: the desired state is reached.
        }
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value()) throw new MissingResource("Zoom HTTP 404");
            if (status == HttpStatus.UNAUTHORIZED.value()) {
                synchronized (this) {
                    token = null;
                }
            }
            log.warn("Zoom rejected request: HTTP {} {}", status, ex.getResponseBodyAsString());
            boolean retryable = status == 401 || status == 429 || status >= 500;
            throw new ProviderException("Zoom HTTP " + status, !retryable);
        } catch (ResourceAccessException ex) {
            throw new ProviderException("Zoom unreachable: " + ex.getMessage(), false);
        }
    }

    /** 404 from Zoom: callers that delete treat it as success, others as a permanent error. */
    static final class MissingResource extends ProviderException {
        MissingResource(String message) {
            super(message, true);
        }
    }
}
