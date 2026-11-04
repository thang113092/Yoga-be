package com.company.yoga.membership.payment.online;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires abandoned links and picks up payments whose webhook was lost. */
@Component
@RequiredArgsConstructor
public class OnlinePaymentReconciliationJob {

    private final OnlinePaymentService onlinePayments;

    @Scheduled(fixedDelayString = "${yoga.payments.reconcile-delay-ms:120000}")
    public void reconcile() {
        onlinePayments.reconcileOpenLinks();
    }
}
