package com.company.yoga.membership.payment.online;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** PayOS VietQR links: https://payos.vn/docs/api/ */
@Component
@Slf4j
public class PayOsGateway implements PaymentGateway {

    static final String PROVIDER = "PAYOS";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TRANSFER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestClient client;
    private final String checksumKey;
    private final boolean configured;

    @Autowired
    public PayOsGateway(@Value("${yoga.payments.payos.base-url:https://api-merchant.payos.vn}") String baseUrl,
                        @Value("${yoga.payments.payos.client-id:}") String clientId,
                        @Value("${yoga.payments.payos.api-key:}") String apiKey,
                        @Value("${yoga.payments.payos.checksum-key:}") String checksumKey) {
        this(buildClient(baseUrl, clientId, apiKey), checksumKey, !clientId.isBlank() && !apiKey.isBlank() && !checksumKey.isBlank());
    }

    PayOsGateway(RestClient client, String checksumKey, boolean configured) {
        this.client = client;
        this.checksumKey = checksumKey;
        this.configured = configured;
    }

    private static RestClient buildClient(String baseUrl, String clientId, String apiKey) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(baseUrl).defaultHeader("x-client-id", clientId).defaultHeader("x-api-key", apiKey)
                .requestFactory(factory).build();
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
    public CreatedLink createLink(LinkRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", r.orderCode());
        body.put("amount", r.amount());
        body.put("description", r.description());
        body.put("cancelUrl", r.cancelUrl());
        body.put("returnUrl", r.returnUrl());
        body.put("expiredAt", r.expiresAt().getEpochSecond());
        body.put("signature", PayOsSignature.paymentRequest(r.amount(), r.cancelUrl(), r.description(), r.orderCode(), r.returnUrl(), checksumKey));
        JsonNode data = call(() -> client.post().uri("/v2/payment-requests").body(body).retrieve().body(JsonNode.class));
        if (!data.hasNonNull("checkoutUrl") || !data.hasNonNull("qrCode")) throw new GatewayException("PayOS response without payment link", false);
        return new CreatedLink(data.path("paymentLinkId").asText(null), data.get("checkoutUrl").asText(), data.get("qrCode").asText());
    }

    @Override
    public LinkStatus getStatus(long orderCode) {
        JsonNode data = call(() -> client.get().uri("/v2/payment-requests/{id}", orderCode).retrieve().body(JsonNode.class));
        String reference = null;
        Instant paidAt = null;
        for (JsonNode transfer : data.path("transactions")) {
            reference = transfer.path("reference").asText(reference);
            paidAt = transferTime(transfer.path("transactionDateTime").asText(null));
        }
        return new LinkStatus(data.path("status").asText(""), data.path("amountPaid").asLong(0), reference, paidAt);
    }

    @Override
    public void cancel(long orderCode, String reason) {
        call(() -> client.post().uri("/v2/payment-requests/{id}/cancel", orderCode)
                .body(Map.of("cancellationReason", reason)).retrieve().body(JsonNode.class));
    }

    @Override
    public Optional<PaidNotice> verifyWebhook(JsonNode body) {
        JsonNode data = body == null ? null : body.get("data");
        if (!configured || data == null || !data.isObject()
                || !PayOsSignature.matches(PayOsSignature.data(data, checksumKey), body.path("signature").asText(null))) {
            return Optional.empty();
        }
        String reference = data.path("reference").asText("");
        String linkId = data.path("paymentLinkId").asText("");
        long orderCode = data.path("orderCode").asLong();
        String event = (linkId.isBlank() ? String.valueOf(orderCode) : linkId) + ":" + reference;
        boolean success = "00".equals(body.path("code").asText()) && "00".equals(data.path("code").asText("00"));
        return Optional.of(new PaidNotice(event, orderCode, data.path("amount").asLong(), reference,
                Optional.ofNullable(transferTime(data.path("transactionDateTime").asText(null))).orElseGet(Instant::now), success));
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        JsonNode response;
        try {
            response = request.get();
        } catch (RestClientResponseException ex) {
            throw new GatewayException("PayOS HTTP " + ex.getStatusCode().value(), ex.getStatusCode().is4xxClientError());
        } catch (ResourceAccessException ex) {
            throw new GatewayException("PayOS unreachable: " + ex.getMessage(), false);
        }
        if (response == null || !"00".equals(response.path("code").asText())) {
            // PayOS reports business errors (unknown order, link exists, ...) with HTTP 200 and a non-00 code.
            String code = response == null ? "empty" : response.path("code").asText();
            log.warn("PayOS rejected request: code={} desc={}", code, response == null ? null : response.path("desc").asText());
            throw new GatewayException("PayOS code " + code, response != null);
        }
        return response.path("data");
    }

    private static Instant transferTime(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text.trim(), TRANSFER_TIME).atZone(VIETNAM).toInstant();
        } catch (java.time.format.DateTimeParseException ex) {
            try {
                return Instant.parse(text.trim());
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
