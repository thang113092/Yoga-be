package com.company.yoga.content;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bunny Stream.
 * <ul>
 *   <li>Upload: TUS to {@code tusupload} with AuthorizationSignature = SHA256(libraryId + apiKey + expires + videoId).</li>
 *   <li>Playback: embed player with token authentication, token = SHA256(tokenKey + videoId + expires).</li>
 * </ul>
 * Empty library id or API key turns the library off; without a token key the embed URL is unsigned
 * (only acceptable while embed token authentication is disabled on the library).
 */
@Component
@Slf4j
public class BunnyStreamProvider implements VideoHostingProvider {

    /** Bunny video status codes. */
    private static final int UPLOADED = 1;
    private static final int FINISHED = 4;
    private static final int ERROR = 5;
    private static final int UPLOAD_FAILED = 6;

    private final RestClient api;
    private final String libraryId;
    private final String apiKey;
    private final String tokenKey;
    private final String cdnHostname;
    private final String embedBaseUrl;
    private final String tusEndpoint;

    @Autowired
    public BunnyStreamProvider(@Value("${yoga.content.bunny.api-base-url:https://video.bunnycdn.com}") String apiBaseUrl,
                               @Value("${yoga.content.bunny.embed-base-url:https://iframe.mediadelivery.net}") String embedBaseUrl,
                               @Value("${yoga.content.bunny.tus-endpoint:https://video.bunnycdn.com/tusupload}") String tusEndpoint,
                               @Value("${yoga.content.bunny.library-id:}") String libraryId,
                               @Value("${yoga.content.bunny.api-key:}") String apiKey,
                               @Value("${yoga.content.bunny.token-key:}") String tokenKey,
                               @Value("${yoga.content.bunny.cdn-hostname:}") String cdnHostname) {
        this(client(apiBaseUrl), embedBaseUrl, tusEndpoint, libraryId, apiKey, tokenKey, cdnHostname);
    }

    BunnyStreamProvider(RestClient api, String embedBaseUrl, String tusEndpoint, String libraryId, String apiKey,
                        String tokenKey, String cdnHostname) {
        this.api = api;
        this.embedBaseUrl = trimSlash(embedBaseUrl);
        this.tusEndpoint = tusEndpoint;
        this.libraryId = libraryId == null ? "" : libraryId.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.tokenKey = tokenKey == null ? "" : tokenKey.trim();
        this.cdnHostname = cdnHostname == null ? "" : cdnHostname.trim();
    }

    private static RestClient client(String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return !libraryId.isBlank() && !apiKey.isBlank();
    }

    @Override
    public String createVideo(String title) {
        String name = title.length() > 200 ? title.substring(0, 200) : title;
        JsonNode created = call(() -> api.post().uri("/library/{lib}/videos", libraryId).header("AccessKey", apiKey)
                .body(Map.of("title", name)).retrieve().body(JsonNode.class));
        if (created == null || !created.hasNonNull("guid")) throw new ProviderException("Bunny response without video guid");
        return created.get("guid").asText();
    }

    @Override
    public UploadTicket uploadTicket(String providerVideoId, Duration ttl) {
        long expires = Instant.now().plus(ttl).getEpochSecond();
        String signature = sha256Hex(libraryId + apiKey + expires + providerVideoId);
        return new UploadTicket(tusEndpoint, libraryId, providerVideoId, signature, expires);
    }

    @Override
    public VideoState state(String providerVideoId) {
        JsonNode video = call(() -> api.get().uri("/library/{lib}/videos/{id}", libraryId, providerVideoId).header("AccessKey", apiKey)
                .retrieve().body(JsonNode.class));
        if (video == null) throw new ProviderException("Empty Bunny response");
        int status = video.path("status").asInt(-1);
        int length = video.path("length").asInt(0);
        String thumbnail = null;
        String file = video.path("thumbnailFileName").asText("");
        if (!cdnHostname.isBlank() && !file.isBlank()) thumbnail = "https://" + cdnHostname + "/" + providerVideoId + "/" + file;
        if (status == FINISHED && length > 0) return new VideoState(Phase.READY, length, thumbnail, null);
        if (status == ERROR) return new VideoState(Phase.FAILED, null, thumbnail, "Bunny không chuyển mã được file này");
        if (status == UPLOAD_FAILED) return new VideoState(Phase.FAILED, null, thumbnail, "Tải file lên Bunny thất bại");
        if (status < UPLOADED) return new VideoState(Phase.WAITING_FOR_UPLOAD, null, thumbnail, null);
        return new VideoState(Phase.PROCESSING, null, thumbnail, null);
    }

    @Override
    public SignedPlayback playback(String providerVideoId, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        StringBuilder url = new StringBuilder(embedBaseUrl).append("/embed/").append(enc(libraryId)).append('/').append(enc(providerVideoId))
                .append("?autoplay=false&preload=true&responsive=true");
        if (!tokenKey.isBlank()) {
            long expires = expiresAt.getEpochSecond();
            url.append("&token=").append(sha256Hex(tokenKey + providerVideoId + expires)).append("&expires=").append(expires);
        }
        return new SignedPlayback(url.toString(), expiresAt);
    }

    /** POST /library/{id}/videos/fetch returns only a status, so the new video is found again by its unique title. */
    @Override
    public void fetchFromUrl(String url, Map<String, String> headers, String title) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("url", url);
        body.put("title", title);
        if (headers != null && !headers.isEmpty()) body.put("headers", headers);
        JsonNode result = call(() -> api.post().uri("/library/{lib}/videos/fetch", libraryId).header("AccessKey", apiKey)
                .body(body).retrieve().body(JsonNode.class));
        if (result != null && result.has("success") && !result.path("success").asBoolean(true)) {
            throw new ProviderException("Bunny fetch refused: " + result.path("message").asText(""));
        }
    }

    @Override
    public java.util.Optional<String> findVideoIdByTitle(String title) {
        JsonNode page = call(() -> api.get().uri(b -> b.path("/library/{lib}/videos").queryParam("search", title)
                        .queryParam("itemsPerPage", 20).build(libraryId))
                .header("AccessKey", apiKey).retrieve().body(JsonNode.class));
        if (page == null) return java.util.Optional.empty();
        for (JsonNode item : page.path("items")) {
            if (title.equals(item.path("title").asText()) && item.hasNonNull("guid")) return java.util.Optional.of(item.get("guid").asText());
        }
        return java.util.Optional.empty();
    }

    @Override
    public void delete(String providerVideoId) {
        try {
            call(() -> api.delete().uri("/library/{lib}/videos/{id}", libraryId, providerVideoId).header("AccessKey", apiKey)
                    .retrieve().toBodilessEntity());
        } catch (MissingVideo ignored) {
            // Already deleted on Bunny.
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404) throw new MissingVideo();
            log.warn("Bunny rejected request: HTTP {}", status);
            throw new ProviderException("Bunny HTTP " + status);
        } catch (ResourceAccessException ex) {
            throw new ProviderException("Bunny unreachable: " + ex.getMessage());
        }
    }

    static final class MissingVideo extends ProviderException {
        MissingVideo() {
            super("Bunny video not found");
        }
    }
}
