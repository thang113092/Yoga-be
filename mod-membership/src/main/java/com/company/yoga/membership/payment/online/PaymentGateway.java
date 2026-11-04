package com.company.yoga.membership.payment.online;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;

/** One online payment provider. Implementations never touch the database. */
public interface PaymentGateway {

    String provider();

    boolean isConfigured();

    CreatedLink createLink(LinkRequest request);

    LinkStatus getStatus(long orderCode);

    void cancel(long orderCode, String reason);

    /** Empty when the callback is not signed with our key. */
    Optional<PaidNotice> verifyWebhook(JsonNode body);

    record LinkRequest(long orderCode, long amount, String description, Instant expiresAt, String returnUrl, String cancelUrl) {}

    record CreatedLink(String linkId, String checkoutUrl, String qrPayload) {}

    /** amountPaid is what the provider has received so far; reference identifies the last transfer. */
    record LinkStatus(String status, long amountPaid, String reference, Instant paidAt) {
        public boolean closed() {
            return "CANCELLED".equals(status) || "EXPIRED".equals(status);
        }
    }

    record PaidNotice(String eventReference, long orderCode, long amount, String reference, Instant paidAt, boolean success) {}

    class GatewayException extends RuntimeException {
        private final boolean clientError;

        public GatewayException(String message, boolean clientError) {
            super(message);
            this.clientError = clientError;
        }

        /** The provider rejected the request itself, e.g. an unknown order code. */
        public boolean isClientError() {
            return clientError;
        }
    }
}
