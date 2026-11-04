package com.company.yoga.membership.payment.online;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint: trust comes only from the gateway signature, never from the caller. */
@Tag(name = "Payment Webhooks", description = "Thông báo từ cổng thanh toán")
@RestController
@RequestMapping("/api/v1/payment-webhooks")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final OnlinePaymentService onlinePayments;

    @PostMapping("/payos")
    @Operation(summary = "Webhook PayOS (xác thực bằng chữ ký HMAC)")
    public ResponseEntity<Map<String, Object>> payos(@RequestBody JsonNode body) {
        if (!onlinePayments.handleWebhook(body)) {
            log.warn("Rejected PayOS webhook with an invalid signature");
            return ResponseEntity.badRequest().body(Map.of("success", false));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
