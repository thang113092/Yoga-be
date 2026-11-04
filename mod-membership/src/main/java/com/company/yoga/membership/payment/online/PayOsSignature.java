package com.company.yoga.membership.payment.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.StringJoiner;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** PayOS HMAC-SHA256 signatures (payment-requests and webhook data; payouts use another scheme). */
final class PayOsSignature {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PayOsSignature() {}

    static String paymentRequest(long amount, String cancelUrl, String description, long orderCode, String returnUrl, String key) {
        return hmac("amount=" + amount + "&cancelUrl=" + cancelUrl + "&description=" + description
                + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl, key);
    }

    /** Keys sorted alphabetically, nulls as empty strings, nested values as JSON with sorted keys. */
    static String data(JsonNode data, String key) {
        StringJoiner query = new StringJoiner("&");
        new TreeMap<String, JsonNode>(fields(data)).forEach((name, value) -> query.add(name + "=" + text(value)));
        return hmac(query.toString(), key);
    }

    static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isContainerNode()) return json(sorted(value));
        String text = value.asText();
        return "null".equals(text) || "undefined".equals(text) ? "" : text;
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(item -> array.add(sorted(item)));
            return array;
        }
        if (!node.isObject()) return node;
        ObjectNode object = JSON.createObjectNode();
        new TreeMap<String, JsonNode>(fields(node)).forEach((name, value) -> object.set(name, sorted(value)));
        return object;
    }

    private static TreeMap<String, JsonNode> fields(JsonNode node) {
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            fields.put(name, node.get(name));
        }
        return fields;
    }

    private static String json(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmac(String message, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
