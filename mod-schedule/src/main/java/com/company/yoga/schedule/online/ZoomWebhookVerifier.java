package com.company.yoga.schedule.online;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Zoom webhook signatures: {@code v0=HMAC_SHA256(secret, "v0:" + timestamp + ":" + body)} in x-zm-signature.
 * https://developers.zoom.us/docs/api/webhooks/#verify-webhook-events
 */
@Component
public class ZoomWebhookVerifier {

    private static final long MAX_SKEW_SECONDS = 300;
    private final String secret;

    public ZoomWebhookVerifier(@Value("${yoga.online.zoom.webhook-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public boolean isConfigured() {
        return !secret.isBlank();
    }

    public boolean verify(String timestamp, String signature, String body) {
        return verify(timestamp, signature, body, Instant.now());
    }

    boolean verify(String timestamp, String signature, String body, Instant now) {
        if (!isConfigured() || timestamp == null || signature == null || body == null) return false;
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        // Zoom sends seconds; accept milliseconds too.
        if (sent > 100_000_000_000L) sent /= 1000;
        if (Math.abs(now.getEpochSecond() - sent) > MAX_SKEW_SECONDS) return false;
        String expected = "v0=" + hmac("v0:" + timestamp.trim() + ":" + body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Answer to Zoom's endpoint.url_validation challenge. */
    public String encryptToken(String plainToken) {
        return hmac(plainToken);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
