package com.company.yoga.membership.order.service;

import com.company.yoga.membership.payment.online.OnlinePaymentService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Steps of a student's online purchase that span the gateway and the order. Deliberately not transactional:
 * the gateway is called outside any database transaction, as in {@link OnlinePaymentService}.
 */
@Service
@RequiredArgsConstructor
public class SelfServiceCheckoutService {

    private final PosOrderService orders;
    private final OnlinePaymentService onlinePayments;

    /** Closes the order's open payment link at the gateway first, so nobody can pay a cancelled order. */
    @PreAuthorize("hasRole('STUDENT')")
    public void cancelOwnOrder(UUID orderId) {
        onlinePayments.openForStudent(orderId).ifPresent(open -> onlinePayments.cancelForStudent(open.id()));
        orders.cancelOwnPendingOrder(orderId);
    }
}
