package com.company.yoga.schedule.online;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ZoomWebhookVerifierTest {

    private static final String SECRET = "zoom-secret";
    private static final String BODY = "{\"event\":\"meeting.started\",\"payload\":{\"object\":{\"id\":\"9001\"}},\"event_ts\":1793760000000}";
    private final ZoomWebhookVerifier verifier = new ZoomWebhookVerifier(SECRET);

    private static String hmac(String key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Chữ ký v0 đúng theo tài liệu Zoom được chấp nhận")
    void validSignature() throws Exception {
        Instant now = Instant.parse("2026-11-04T04:00:00Z");
        String ts = String.valueOf(now.getEpochSecond());
        assertThat(verifier.verify(ts, "v0=" + hmac(SECRET, "v0:" + ts + ":" + BODY), BODY, now)).isTrue();
    }

    @Test
    @DisplayName("Sai khóa, sửa nội dung hoặc thiếu header đều bị từ chối")
    void invalidSignatures() throws Exception {
        Instant now = Instant.parse("2026-11-04T04:00:00Z");
        String ts = String.valueOf(now.getEpochSecond());
        assertThat(verifier.verify(ts, "v0=" + hmac("other", "v0:" + ts + ":" + BODY), BODY, now)).isFalse();
        assertThat(verifier.verify(ts, "v0=" + hmac(SECRET, "v0:" + ts + ":" + BODY), BODY + " ", now)).isFalse();
        assertThat(verifier.verify(null, "v0=x", BODY, now)).isFalse();
        assertThat(verifier.verify(ts, null, BODY, now)).isFalse();
        assertThat(new ZoomWebhookVerifier("").verify(ts, "v0=" + hmac(SECRET, "v0:" + ts + ":" + BODY), BODY, now)).isFalse();
    }

    @Test
    @DisplayName("Webhook cũ hơn 5 phút bị từ chối để chống phát lại")
    void staleTimestamp() throws Exception {
        Instant sent = Instant.parse("2026-11-04T04:00:00Z");
        String ts = String.valueOf(sent.getEpochSecond());
        String signature = "v0=" + hmac(SECRET, "v0:" + ts + ":" + BODY);
        assertThat(verifier.verify(ts, signature, BODY, sent.plusSeconds(301))).isFalse();
        assertThat(verifier.verify(ts, signature, BODY, sent.plusSeconds(299))).isTrue();
    }

    @Test
    @DisplayName("Trả lời thử thách xác thực URL bằng HMAC của plainToken")
    void urlValidation() throws Exception {
        assertThat(verifier.encryptToken("abc123")).isEqualTo(hmac(SECRET, "abc123"));
    }
}
