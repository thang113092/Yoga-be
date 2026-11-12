package com.company.yoga.membership.payment.online;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.order.entity.OrderEntity;
import com.company.yoga.membership.order.entity.PaymentEntity;
import com.company.yoga.membership.order.repository.OrderItemRepository;
import com.company.yoga.membership.order.repository.OrderRepository;
import com.company.yoga.membership.order.repository.PaymentRepository;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Online payment attempts. Each step runs in its own short transaction so that gateway calls never hold
 * database locks, and money reported by the gateway is recorded even when the order can no longer be activated.
 * Lock order matches PaymentService: payment idempotency key, then order, then the link row.
 */
@Service
@Slf4j
public class OnlinePaymentService {

    private static final Duration MIN_LINK_LIFETIME = Duration.ofMinutes(3);

    private final PaymentGateway gateway;
    private final OnlinePaymentTransactionRepository links;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final MembershipRepository memberships;
    private final AccessPolicy accessPolicy;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final int linkTtlMinutes;
    private final String returnUrl;
    private final String studentReturnUrl;
    private final String studentCourseReturnUrl;
    private UUID gatewayActor;

    public OnlinePaymentService(PaymentGateway gateway, OnlinePaymentTransactionRepository links, PaymentRepository payments,
                                OrderRepository orders, OrderItemRepository orderItems, MembershipRepository memberships,
                                AccessPolicy accessPolicy, EntityManager entityManager, TransactionTemplate transactions,
                                @Value("${yoga.payments.link-ttl-minutes:15}") int linkTtlMinutes,
                                @Value("${yoga.payments.return-url:http://localhost:4300/membership/pos}") String returnUrl,
                                @Value("${yoga.payments.student-return-url:http://localhost:4300/membership/plans}") String studentReturnUrl,
                                @Value("${yoga.payments.student-course-return-url:http://localhost:4300/schedule/courses}") String studentCourseReturnUrl) {
        this.gateway = gateway;
        this.links = links;
        this.payments = payments;
        this.orders = orders;
        this.orderItems = orderItems;
        this.memberships = memberships;
        this.accessPolicy = accessPolicy;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.linkTtlMinutes = linkTtlMinutes;
        this.returnUrl = returnUrl;
        this.studentReturnUrl = studentReturnUrl;
        this.studentCourseReturnUrl = studentCourseReturnUrl;
    }

    /** VietQR shown at the counter. Repeating the call with the same key, or while a link is open, returns that link. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public OnlinePaymentDto.Resp startAtCounter(UUID orderId, UUID idempotencyKey) {
        return start(orderId, idempotencyKey, order -> accessPolicy.requireStaffBranch(order.getBranchId()), returnUrl);
    }

    /** A student paying their own pending order online. Same reuse rules as at the counter. */
    @PreAuthorize("hasRole('STUDENT')")
    public OnlinePaymentDto.Resp startForStudent(UUID orderId, UUID idempotencyKey) {
        boolean course = orderItems.findByOrderId(orderId).stream().anyMatch(i -> "COURSE_CLASS".equals(i.getItemType()));
        return start(orderId, idempotencyKey, this::requireOwnOrder, course ? studentCourseReturnUrl : studentReturnUrl);
    }

    /** The open link of a student's order, if any. */
    @PreAuthorize("hasRole('STUDENT')")
    public Optional<OnlinePaymentDto.Resp> openForStudent(UUID orderId) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new BusinessException(MembershipResultCodes.ORDER_NOT_FOUND));
        requireOwnOrder(order);
        return links.findOpenByOrderId(orderId).map(l -> view(l.getId()));
    }

    @PreAuthorize("hasRole('STUDENT')")
    public OnlinePaymentDto.Resp getForStudent(UUID id) {
        requireOwnLink(find(id));
        return view(id);
    }

    /** Ask the gateway directly, e.g. when the student comes back from the gateway's page. */
    @PreAuthorize("hasRole('STUDENT')")
    public OnlinePaymentDto.Resp refreshForStudent(UUID id) {
        OnlinePaymentTransactionEntity link = find(id);
        requireOwnLink(link);
        try {
            reconcile(link);
        } catch (PaymentGateway.GatewayException ex) {
            throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR);
        }
        return view(id);
    }

    @PreAuthorize("hasRole('STUDENT')")
    public OnlinePaymentDto.Resp cancelForStudent(UUID id) {
        OnlinePaymentTransactionEntity link = find(id);
        return cancelLink(link, requireOwnLink(link), "Học viên hủy thanh toán");
    }

    private OnlinePaymentDto.Resp start(UUID orderId, UUID idempotencyKey, Function<OrderEntity, UUID> authorize, String linkReturnUrl) {
        if (!gateway.isConfigured()) throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_UNAVAILABLE);
        OnlinePaymentTransactionEntity link = transactions.execute(status -> {
            lockKey(idempotencyKey);
            var previous = payments.findByIdempotencyKey(idempotencyKey);
            OrderEntity order = orders.findByIdWithLock(orderId)
                    .orElseThrow(() -> new BusinessException(MembershipResultCodes.ORDER_NOT_FOUND));
            UUID actor = authorize.apply(order);
            setActor(actor);
            if (previous.isPresent()) {
                PaymentEntity p = previous.get();
                if (!Objects.equals(p.getOrderId(), orderId) || !gateway.provider().equals(p.getPaymentMethod()))
                    throw new BusinessException(CommonErrorCode.CONFLICT, "Idempotency key belongs to a different payment");
                return links.findByPaymentId(p.getId()).orElseThrow(() -> new BusinessException(CommonErrorCode.CONFLICT));
            }
            var open = links.findOpenByOrderId(orderId);
            if (open.isPresent()) return open.get();
            if ("PAID".equals(order.getStatus())) throw new BusinessException(MembershipResultCodes.ORDER_ALREADY_PAID);
            if (!"PENDING".equals(order.getStatus())) throw new BusinessException(CommonErrorCode.CONFLICT);
            return createAttempt(order, actor, idempotencyKey);
        });
        if (link.getCheckoutUrl() == null) {
            if (link.getCreatedAt().isBefore(Instant.now().minusSeconds(30)))
                throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR);
            attachLink(link, linkReturnUrl);
        }
        return view(link.getId());
    }

    /** Students act only on orders they are the customer of; anything else looks like it does not exist. */
    private UUID requireOwnOrder(OrderEntity order) {
        var caller = accessPolicy.actor();
        if (!"STUDENT".equals(accessPolicy.role(caller)) || !Objects.equals(order.getCustomerId(), caller.getId()))
            throw new BusinessException(MembershipResultCodes.ORDER_NOT_FOUND);
        return caller.getId();
    }

    private UUID requireOwnLink(OnlinePaymentTransactionEntity link) {
        var caller = accessPolicy.actor();
        UUID customer = payments.findById(link.getPaymentId()).flatMap(p -> orders.findById(p.getOrderId()))
                .map(OrderEntity::getCustomerId).orElse(null);
        if (!"STUDENT".equals(accessPolicy.role(caller)) || !Objects.equals(customer, caller.getId()))
            throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_NOT_FOUND);
        return caller.getId();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public OnlinePaymentDto.Resp get(UUID id) {
        accessPolicy.requireStaffBranch(find(id).getBranchId());
        return view(id);
    }

    /** Ask the gateway directly; covers a missed webhook without waiting for the reconciliation job. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public OnlinePaymentDto.Resp refresh(UUID id) {
        OnlinePaymentTransactionEntity link = find(id);
        accessPolicy.requireStaffBranch(link.getBranchId());
        try {
            reconcile(link);
        } catch (PaymentGateway.GatewayException ex) {
            throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR);
        }
        return view(id);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public OnlinePaymentDto.Resp cancel(UUID id) {
        OnlinePaymentTransactionEntity link = find(id);
        return cancelLink(link, accessPolicy.requireStaffBranch(link.getBranchId()), "Nhân viên hủy mã thanh toán");
    }

    private OnlinePaymentDto.Resp cancelLink(OnlinePaymentTransactionEntity link, UUID actor, String reason) {
        UUID id = link.getId();
        if (!"CREATED".equals(link.getStatus())) return view(id);
        try {
            gateway.cancel(link.getProviderOrderCode(), reason);
        } catch (PaymentGateway.GatewayException ex) {
            // The customer may have paid just now; never close a link the gateway still holds money for.
            log.warn("Cancelling online payment {} failed: {}", id, ex.getMessage());
            reconcile(link);
            OnlinePaymentDto.Resp current = view(id);
            if ("CREATED".equals(current.status())) throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR);
            return current;
        }
        close(id, "CANCELLED", actor);
        return view(id);
    }

    /** False when the callback is not signed by the gateway. Redelivered events are acknowledged without effect. */
    public boolean handleWebhook(JsonNode body) {
        var verified = gateway.verifyWebhook(body);
        if (verified.isEmpty()) return false;
        PaymentGateway.PaidNotice notice = verified.get();
        UUID eventId = transactions.execute(status -> storeEvent(notice, body));
        if (eventId == null) return true;
        if (!notice.success()) {
            transactions.executeWithoutResult(status -> finishEvent(eventId, "IGNORED", "Gateway reported an unsuccessful transfer"));
            return true;
        }
        recordMoney(notice.orderCode(), notice.amount(), notice.reference(), notice.paidAt(), eventId);
        return true;
    }

    /** Closes expired links and catches payments whose webhook never arrived. */
    public void reconcileOpenLinks() {
        if (!gateway.isConfigured()) return;
        for (OnlinePaymentTransactionEntity link : links.findOpenCreatedBefore(Instant.now().minusSeconds(60), PageRequest.of(0, 50))) {
            try {
                reconcile(link);
            } catch (RuntimeException ex) {
                log.warn("Online payment {} could not be reconciled yet: {}", link.getId(), ex.getMessage());
            }
        }
    }

    private void reconcile(OnlinePaymentTransactionEntity link) {
        if (!"CREATED".equals(link.getStatus())) return;
        PaymentGateway.LinkStatus remote;
        try {
            remote = gateway.getStatus(link.getProviderOrderCode());
        } catch (PaymentGateway.GatewayException ex) {
            // The link creation call failed before the gateway stored it: nobody can pay it.
            if (ex.isClientError() && link.getCheckoutUrl() == null) {
                close(link.getId(), "CANCELLED", gatewayActor());
                return;
            }
            throw ex;
        }
        boolean expired = !Instant.now().isBefore(link.getExpiresAt());
        if (remote.amountPaid() > 0 && (remote.amountPaid() >= link.getAmount().longValue() || expired || remote.closed())) {
            String reference = remote.reference() != null ? remote.reference() : gateway.provider() + "-" + link.getProviderOrderCode();
            recordMoney(link.getProviderOrderCode(), remote.amountPaid(), reference,
                    remote.paidAt() != null ? remote.paidAt() : Instant.now(), null);
            return;
        }
        if (remote.closed()) {
            close(link.getId(), "CANCELLED".equals(remote.status()) && !expired ? "CANCELLED" : "EXPIRED", gatewayActor());
        } else if (expired) {
            gateway.cancel(link.getProviderOrderCode(), "Hết hạn thanh toán");
            close(link.getId(), "EXPIRED", gatewayActor());
        }
    }

    private OnlinePaymentTransactionEntity createAttempt(OrderEntity order, UUID actor, UUID idempotencyKey) {
        BigDecimal total = order.getTotalAmount();
        if (total.signum() <= 0 || total.stripTrailingZeros().scale() > 0) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        if (payments.findByOrderId(order.getId()).stream().anyMatch(p -> "SUCCESS".equals(p.getPaymentStatus())))
            throw new BusinessException(CommonErrorCode.CONFLICT);
        Instant expiresAt = deadline(order.getId());
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentCode("PAY-" + UUID.randomUUID());
        payment.setOrderId(order.getId()); payment.setBranchId(order.getBranchId()); payment.setAmount(total);
        payment.setPaymentMethod(gateway.provider()); payment.setPaymentStatus("PENDING"); payment.setIdempotencyKey(idempotencyKey);
        payment.setCashierId(actor); payment.setPaymentTime(Instant.now()); payment.setNotes("VietQR động");
        payment = payments.saveAndFlush(payment);
        OnlinePaymentTransactionEntity link = new OnlinePaymentTransactionEntity();
        link.setPaymentId(payment.getId()); link.setBranchId(order.getBranchId()); link.setProvider(gateway.provider());
        link.setAmount(total); link.setExpiresAt(expiresAt);
        return links.saveAndFlush(link);
    }

    /** Membership activation requires today's start date; course seats require an unexpired hold. */
    private Instant deadline(UUID orderId) {
        Object millis = entityManager.createNativeQuery("""
                SELECT CAST(EXTRACT(EPOCH FROM CASE WHEN EXISTS(
                    SELECT 1 FROM yoga.memberships m JOIN yoga.order_items i ON i.id=m.source_order_item_id
                    WHERE i.order_id=:order AND m.start_date<>(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE) THEN NULL
                  ELSE LEAST(CURRENT_TIMESTAMP + make_interval(mins => :ttl),
                    CASE WHEN EXISTS(SELECT 1 FROM yoga.order_items WHERE order_id=:order AND item_type='MEMBERSHIP_PLAN')
                      THEN ((((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE+1)::TIMESTAMP) AT TIME ZONE 'Asia/Ho_Chi_Minh')
                           - INTERVAL '5 minutes' END,
                    (SELECT min(e.reserved_until) - INTERVAL '2 minutes' FROM yoga.course_enrollments e
                      JOIN yoga.order_items i ON i.id=e.source_order_item_id WHERE i.order_id=:order)) END) * 1000 AS BIGINT)
                """).setParameter("order", orderId).setParameter("ttl", linkTtlMinutes).getSingleResult();
        Instant deadline = millis == null ? null : Instant.ofEpochMilli(((Number) millis).longValue()).truncatedTo(ChronoUnit.SECONDS);
        if (deadline == null || deadline.isBefore(Instant.now().plus(MIN_LINK_LIFETIME)))
            throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_WINDOW_CLOSED);
        return deadline;
    }

    private void attachLink(OnlinePaymentTransactionEntity link, String linkReturnUrl) {
        PaymentGateway.CreatedLink created;
        try {
            created = gateway.createLink(new PaymentGateway.LinkRequest(link.getProviderOrderCode(), link.getAmount().longValueExact(),
                    "YG" + link.getProviderOrderCode(), link.getExpiresAt(), linkReturnUrl, linkReturnUrl));
        } catch (PaymentGateway.GatewayException ex) {
            log.warn("Creating payment link for {} failed: {}", link.getId(), ex.getMessage());
            // A concurrent request for the same attempt may have created the link first: the gateway then rejects this
            // duplicate, but the customer can pay the winner's QR. Leave the attempt open; a retry returns that link,
            // and reconciliation expires it if the winner never stored it.
            if (ex.isClientError() && gatewayHoldsOpenLink(link.getProviderOrderCode()))
                throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR);
            close(link.getId(), "CANCELLED", gatewayActor());
            throw new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR);
        }
        transactions.executeWithoutResult(status -> {
            OnlinePaymentTransactionEntity locked = links.findByIdForUpdate(link.getId()).orElseThrow();
            locked.setProviderLinkId(created.linkId());
            locked.setCheckoutUrl(created.checkoutUrl());
            locked.setQrPayload(created.qrPayload());
            links.saveAndFlush(locked);
        });
    }

    private boolean gatewayHoldsOpenLink(long orderCode) {
        try {
            return !gateway.getStatus(orderCode).closed();
        } catch (PaymentGateway.GatewayException ex) {
            return false;
        }
    }

    /** Ends an unpaid attempt and its pending payment so the order can be collected again or cancelled. */
    private void close(UUID linkId, String outcome, UUID actor) {
        transactions.executeWithoutResult(status -> {
            setActor(actor);
            Locked l = lock(linkId);
            if (!"CREATED".equals(l.link().getStatus())) return;
            l.link().setStatus(outcome);
            links.saveAndFlush(l.link());
            l.payment().setPaymentStatus("FAILED");
            payments.saveAndFlush(l.payment());
        });
    }

    /** Money received by the gateway. Activates the order, or keeps the money visible for reconciliation. */
    private void recordMoney(long orderCode, long amount, String reference, Instant paidAt, UUID eventId) {
        var found = links.findByProviderAndProviderOrderCode(gateway.provider(), orderCode);
        if (found.isEmpty()) {
            if (eventId != null) transactions.executeWithoutResult(status -> finishEvent(eventId, "IGNORED", "Unknown order code"));
            return;
        }
        UUID linkId = found.get().getId();
        try {
            transactions.executeWithoutResult(status -> confirm(linkId, amount, reference, paidAt, eventId));
        } catch (RuntimeException ex) {
            // Typically the order can no longer be activated (day changed, hold expired, plan withdrawn).
            log.warn("Online payment {} received money but could not be applied: {}", linkId, ex.getMessage());
            transactions.executeWithoutResult(status -> needsReconciliation(linkId, amount, reference, paidAt, eventId,
                    "Không kích hoạt được đơn: " + rootMessage(ex)));
        }
    }

    private void confirm(UUID linkId, long amount, String reference, Instant paidAt, UUID eventId) {
        setActor(gatewayActor());
        Locked l = lock(linkId);
        OnlinePaymentTransactionEntity link = l.link();
        switch (link.getStatus()) {
            case "PAID" -> finishEvent(eventId, Objects.equals(reference, link.getProviderReference()) ? "IGNORED" : "NEEDS_RECONCILIATION",
                    Objects.equals(reference, link.getProviderReference()) ? "Already confirmed" : "Nhận thêm tiền cho mã đã thanh toán");
            case "NEEDS_RECONCILIATION", "RESOLVED" -> finishEvent(eventId, "NEEDS_RECONCILIATION", "Nhận thêm tiền cho mã đang đối soát");
            case "CREATED" -> {
                if (BigDecimal.valueOf(amount).compareTo(link.getAmount()) != 0) {
                    markReconciliation(l, amount, reference, paidAt);
                    finishEvent(eventId, "NEEDS_RECONCILIATION", "Số tiền nhận không khớp đơn hàng");
                    return;
                }
                link.setStatus("PAID");
                link.setPaidAmount(link.getAmount()); link.setPaidAt(paidAt); link.setProviderReference(reference);
                links.saveAndFlush(link);
                l.payment().setPaymentStatus("SUCCESS");
                l.payment().setConfirmedBy(gatewayActor());
                l.payment().setConfirmedAt(Instant.now());
                payments.saveAndFlush(l.payment());
                finishEvent(eventId, "CONFIRMED", null);
            }
            default -> {
                markReconciliation(l, amount, reference, paidAt);
                finishEvent(eventId, "NEEDS_RECONCILIATION", "Tiền về sau khi mã thanh toán đã đóng");
            }
        }
    }

    private void needsReconciliation(UUID linkId, long amount, String reference, Instant paidAt, UUID eventId, String reason) {
        setActor(gatewayActor());
        Locked l = lock(linkId);
        if (!List.of("CREATED", "EXPIRED", "CANCELLED").contains(l.link().getStatus())) return;
        markReconciliation(l, amount, reference, paidAt);
        finishEvent(eventId, "NEEDS_RECONCILIATION", reason);
    }

    private void markReconciliation(Locked l, long amount, String reference, Instant paidAt) {
        boolean wasOpen = "CREATED".equals(l.link().getStatus());
        l.link().setStatus("NEEDS_RECONCILIATION");
        l.link().setPaidAmount(BigDecimal.valueOf(amount)); l.link().setPaidAt(paidAt); l.link().setProviderReference(reference);
        links.saveAndFlush(l.link());
        if (wasOpen) {
            l.payment().setPaymentStatus("FAILED");
            payments.saveAndFlush(l.payment());
        }
        log.warn("Online payment {} needs manual reconciliation ({} VND, ref {})", l.link().getId(), amount, reference);
    }

    private record Locked(OnlinePaymentTransactionEntity link, PaymentEntity payment) {}

    private Locked lock(UUID linkId) {
        OnlinePaymentTransactionEntity snapshot = find(linkId);
        PaymentEntity payment = payments.findById(snapshot.getPaymentId()).orElseThrow();
        lockKey(payment.getIdempotencyKey());
        orders.findByIdWithLock(payment.getOrderId()).orElseThrow();
        OnlinePaymentTransactionEntity link = links.findByIdForUpdate(linkId).orElseThrow();
        entityManager.refresh(link);
        entityManager.refresh(payment);
        return new Locked(link, payment);
    }

    private UUID storeEvent(PaymentGateway.PaidNotice notice, JsonNode body) {
        List<?> ids = entityManager.createNativeQuery("""
                INSERT INTO yoga.payment_gateway_events(provider,event_reference,provider_order_code,payload)
                VALUES(:provider,:reference,:orderCode,CAST(:payload AS jsonb))
                ON CONFLICT (provider,event_reference) DO NOTHING RETURNING id
                """).setParameter("provider", gateway.provider()).setParameter("reference", notice.eventReference())
                .setParameter("orderCode", notice.orderCode()).setParameter("payload", body.toString()).getResultList();
        return ids.isEmpty() ? null : (UUID) ids.get(0);
    }

    private void finishEvent(UUID eventId, String result, String error) {
        if (eventId == null) return;
        entityManager.createNativeQuery("""
                UPDATE yoga.payment_gateway_events SET processed_at=CURRENT_TIMESTAMP,result=:result,error_message=:error
                WHERE id=:id AND processed_at IS NULL
                """).setParameter("result", result).setParameter("error", error).setParameter("id", eventId).executeUpdate();
    }

    private OnlinePaymentDto.Resp view(UUID id) {
        OnlinePaymentTransactionEntity link = find(id);
        PaymentEntity payment = payments.findById(link.getPaymentId()).orElseThrow();
        OrderEntity order = orders.findById(payment.getOrderId()).orElseThrow();
        String membershipCode = !"PAID".equals(order.getStatus()) ? null : orderItems.findByOrderId(order.getId()).stream()
                .filter(i -> "MEMBERSHIP_PLAN".equals(i.getItemType()))
                .map(i -> memberships.findBySourceOrderItemId(i.getId())).flatMap(java.util.Optional::stream)
                .map(m -> m.getMembershipCode()).findFirst().orElse(null);
        boolean open = "CREATED".equals(link.getStatus());
        return new OnlinePaymentDto.Resp(link.getId(), payment.getId(), order.getId(), link.getProvider(), link.getProviderOrderCode(),
                link.getStatus(), link.getAmount(), open ? link.getCheckoutUrl() : null, open ? link.getQrPayload() : null,
                link.getExpiresAt(), link.getPaidAt(), payment.getPaymentStatus(), order.getStatus(), membershipCode);
    }

    private OnlinePaymentTransactionEntity find(UUID id) {
        return links.findById(id).orElseThrow(() -> new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_NOT_FOUND));
    }

    private void lockKey(UUID key) {
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 0))")
                .setParameter("key", key.toString()).getResultList();
    }

    private void setActor(UUID actor) {
        entityManager.createNativeQuery("SELECT set_config('app.actor_id', :actor, true)")
                .setParameter("actor", actor.toString()).getResultList();
    }

    private UUID gatewayActor() {
        if (gatewayActor == null) {
            gatewayActor = (UUID) entityManager.createNativeQuery("SELECT yoga.payment_gateway_actor()").getSingleResult();
        }
        return gatewayActor;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null) cur = cur.getCause();
        String message = cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
