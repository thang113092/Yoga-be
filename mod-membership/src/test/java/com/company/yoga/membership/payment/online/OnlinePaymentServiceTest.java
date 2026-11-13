package com.company.yoga.membership.payment.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.order.entity.OrderEntity;
import com.company.yoga.membership.order.entity.PaymentEntity;
import com.company.yoga.membership.order.repository.OrderItemRepository;
import com.company.yoga.membership.order.repository.OrderRepository;
import com.company.yoga.membership.order.repository.PaymentRepository;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OnlinePaymentServiceTest {

    private static final UUID ACTOR = new UUID(0, 0xa1);
    private static final UUID EVENT = UUID.randomUUID();

    private PaymentGateway gateway;
    private OnlinePaymentTransactionRepository links;
    private PaymentRepository payments;
    private OrderRepository orders;
    private AccessPolicy policy;
    private OrderItemRepository orderItems;
    private Query eventInsert;
    private OnlinePaymentService service;

    // Committed state; each lookup returns a fresh copy like a new persistence context would.
    private OnlinePaymentTransactionEntity storedLink;
    private PaymentEntity storedPayment;
    private OrderEntity order;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGateway.class);
        links = mock(OnlinePaymentTransactionRepository.class);
        payments = mock(PaymentRepository.class);
        orders = mock(OrderRepository.class);
        orderItems = mock(OrderItemRepository.class);
        policy = mock(AccessPolicy.class);
        var em = mock(EntityManager.class);
        var query = mock(Query.class);
        eventInsert = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).contains("INSERT INTO yoga.payment_gateway_events") ? eventInsert : query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(query.getSingleResult()).thenReturn(ACTOR);
        when(eventInsert.setParameter(anyString(), any())).thenReturn(eventInsert);
        when(eventInsert.getResultList()).thenReturn(List.of(EVENT));
        when(gateway.provider()).thenReturn("PAYOS");
        when(gateway.isConfigured()).thenReturn(true);
        when(policy.requireStaffBranch(any())).thenReturn(UUID.randomUUID());
        when(orderItems.findByOrderId(any())).thenReturn(List.of());

        order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setStatus("PENDING");
        order.setTotalAmount(new BigDecimal("2000.00"));
        storedPayment = new PaymentEntity();
        storedPayment.setId(UUID.randomUUID());
        storedPayment.setOrderId(order.getId());
        storedPayment.setIdempotencyKey(UUID.randomUUID());
        storedPayment.setAmount(order.getTotalAmount());
        storedPayment.setPaymentMethod("PAYOS");
        storedPayment.setPaymentStatus("PENDING");
        storedLink = new OnlinePaymentTransactionEntity();
        storedLink.setId(UUID.randomUUID());
        storedLink.setPaymentId(storedPayment.getId());
        storedLink.setProvider("PAYOS");
        storedLink.setProviderOrderCode(100001L);
        storedLink.setAmount(order.getTotalAmount());
        storedLink.setExpiresAt(Instant.now().plusSeconds(600));
        storedLink.setCheckoutUrl("https://pay/1");
        storedLink.setCreatedAt(Instant.now().minusSeconds(120));

        when(links.findById(any())).thenAnswer(inv -> Optional.of(copyLink(storedLink)));
        when(links.findByIdForUpdate(any())).thenAnswer(inv -> Optional.of(copyLink(storedLink)));
        when(links.findByProviderAndProviderOrderCode("PAYOS", 100001L)).thenAnswer(inv -> Optional.of(copyLink(storedLink)));
        when(links.saveAndFlush(any())).thenAnswer(inv -> storedLink = copyLink(inv.getArgument(0)));
        when(payments.findById(any())).thenAnswer(inv -> Optional.of(copyPayment(storedPayment)));
        when(payments.saveAndFlush(any())).thenAnswer(inv -> storedPayment = copyPayment(inv.getArgument(0)));
        when(orders.findByIdWithLock(any())).thenReturn(Optional.of(order));
        when(orders.findById(any())).thenReturn(Optional.of(order));

        service = new OnlinePaymentService(gateway, links, payments, orders, orderItems, mock(MembershipRepository.class), policy, em,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), 15, "https://app/pos", "https://app/plans", "https://app/courses", 10);
    }

    @Test
    @DisplayName("Webhook không về: màn hình đang hỏi trạng thái tự hỏi cổng và xác nhận khoản đã trả")
    void poll_missedWebhook_confirmsFromGateway() {
        when(gateway.getStatus(100001L)).thenReturn(new PaymentGateway.LinkStatus("PAID", 2000, "FT5", Instant.now()));

        var resp = service.get(storedLink.getId());

        assertThat(resp.status()).isEqualTo("PAID");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Hỏi trạng thái liên tục chỉ gọi cổng một lần mỗi khoảng chờ")
    void poll_asksGatewayAtMostOncePerInterval() {
        when(gateway.getStatus(100001L)).thenReturn(new PaymentGateway.LinkStatus("PENDING", 0, null, null));

        service.get(storedLink.getId());
        service.get(storedLink.getId());
        service.get(storedLink.getId());

        verify(gateway, times(1)).getStatus(100001L);
        assertThat(storedLink.getStatus()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("Cổng lỗi khi hỏi trạng thái thì vẫn trả trạng thái đang lưu, không báo lỗi")
    void poll_gatewayError_returnsStoredStatus() {
        when(gateway.getStatus(100001L)).thenThrow(new PaymentGateway.GatewayException("timeout", false));

        assertThat(service.get(storedLink.getId()).status()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("Mã đã thanh toán thì hỏi trạng thái không gọi cổng")
    void poll_closedLink_doesNotAskGateway() {
        storedLink.setStatus("PAID");

        service.get(storedLink.getId());

        verify(gateway, never()).getStatus(anyLong());
    }

    @Test
    @DisplayName("Chưa cấu hình cổng thanh toán thì không tạo mã VietQR")
    void start_requiresConfiguredGateway() {
        when(gateway.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.startAtCounter(order.getId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(MembershipResultCodes.ONLINE_PAYMENT_UNAVAILABLE));
    }

    @Test
    @DisplayName("Đơn đang có mã mở thì trả lại đúng mã đó, không tạo khoản thu mới")
    void start_reusesOpenLink() {
        when(payments.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(links.findOpenByOrderId(order.getId())).thenAnswer(inv -> Optional.of(copyLink(storedLink)));

        var resp = service.startAtCounter(order.getId(), UUID.randomUUID());

        assertThat(resp.id()).isEqualTo(storedLink.getId());
        assertThat(resp.checkoutUrl()).isEqualTo("https://pay/1");
        verify(gateway, never()).createLink(any());
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Webhook sai chữ ký bị từ chối và không ghi gì")
    void webhook_invalidSignature_isRejected() {
        when(gateway.verifyWebhook(any())).thenReturn(Optional.empty());

        assertThat(service.handleWebhook(new ObjectMapper().createObjectNode())).isFalse();
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Webhook gửi lặp chỉ được ghi nhận, không xử lý lại")
    void webhook_redelivery_isAcknowledgedOnly() {
        when(gateway.verifyWebhook(any())).thenReturn(Optional.of(notice(2000)));
        when(eventInsert.getResultList()).thenReturn(List.of());

        assertThat(service.handleWebhook(new ObjectMapper().createObjectNode())).isTrue();
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Webhook đủ tiền xác nhận khoản thu bằng tài khoản hệ thống")
    void webhook_fullAmount_confirmsPayment() {
        when(gateway.verifyWebhook(any())).thenReturn(Optional.of(notice(2000)));

        service.handleWebhook(new ObjectMapper().createObjectNode());

        assertThat(storedLink.getStatus()).isEqualTo("PAID");
        assertThat(storedLink.getProviderReference()).isEqualTo("FT1");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("SUCCESS");
        assertThat(storedPayment.getConfirmedBy()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("Số tiền nhận khác đơn hàng thì chuyển đối soát, không kích hoạt")
    void webhook_amountMismatch_needsReconciliation() {
        when(gateway.verifyWebhook(any())).thenReturn(Optional.of(notice(1500)));

        service.handleWebhook(new ObjectMapper().createObjectNode());

        assertThat(storedLink.getStatus()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(storedLink.getPaidAmount()).isEqualByComparingTo("1500");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Database từ chối kích hoạt (qua ngày, hết giữ chỗ) thì tiền vẫn được ghi để đối soát")
    void webhook_activationRejected_keepsMoneyForReconciliation() {
        when(gateway.verifyWebhook(any())).thenReturn(Optional.of(notice(2000)));
        doAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            if ("SUCCESS".equals(p.getPaymentStatus())) throw new DataIntegrityViolationException("Course reservation expired or closed");
            return storedPayment = copyPayment(p);
        }).when(payments).saveAndFlush(any());
        // The rolled-back PAID update must not survive: restore what the database would still hold.
        doAnswer(inv -> {
            OnlinePaymentTransactionEntity l = inv.getArgument(0);
            if (!"PAID".equals(l.getStatus())) storedLink = copyLink(l);
            return l;
        }).when(links).saveAndFlush(any());

        service.handleWebhook(new ObjectMapper().createObjectNode());

        assertThat(storedLink.getStatus()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(storedLink.getProviderReference()).isEqualTo("FT1");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Tiền về sau khi mã đã hết hạn thì chuyển đối soát")
    void webhook_afterExpiry_needsReconciliation() {
        storedLink.setStatus("EXPIRED");
        storedPayment.setPaymentStatus("FAILED");
        when(gateway.verifyWebhook(any())).thenReturn(Optional.of(notice(2000)));

        service.handleWebhook(new ObjectMapper().createObjectNode());

        assertThat(storedLink.getStatus()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Đối soát định kỳ: mã hết hạn chưa có tiền thì hủy trên cổng và đóng khoản thu")
    void reconcile_expiredUnpaidLink_isClosed() {
        storedLink.setExpiresAt(Instant.now().minusSeconds(5));
        when(links.findOpenCreatedBefore(any(), any())).thenAnswer(inv -> List.of(copyLink(storedLink)));
        when(gateway.getStatus(100001L)).thenReturn(new PaymentGateway.LinkStatus("PENDING", 0, null, null));

        service.reconcileOpenLinks();

        verify(gateway).cancel(anyLong(), anyString());
        assertThat(storedLink.getStatus()).isEqualTo("EXPIRED");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Đối soát định kỳ: webhook bị mất nhưng cổng báo đã nhận đủ tiền thì xác nhận")
    void reconcile_missedWebhook_confirms() {
        when(links.findOpenCreatedBefore(any(), any())).thenAnswer(inv -> List.of(copyLink(storedLink)));
        when(gateway.getStatus(100001L)).thenReturn(new PaymentGateway.LinkStatus("PAID", 2000, "FT9", Instant.now()));

        service.reconcileOpenLinks();

        assertThat(storedLink.getStatus()).isEqualTo("PAID");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("SUCCESS");
        verify(gateway, never()).cancel(anyLong(), anyString());
    }

    @Test
    @DisplayName("Hủy mã thất bại vì khách vừa trả tiền thì xác nhận thay vì đóng")
    void cancel_racingPayment_confirmsInstead() {
        doThrow(new PaymentGateway.GatewayException("PayOS code 101", true)).when(gateway).cancel(anyLong(), anyString());
        when(gateway.getStatus(100001L)).thenReturn(new PaymentGateway.LinkStatus("PAID", 2000, "FT7", Instant.now()));

        var resp = service.cancel(storedLink.getId());

        assertThat(resp.status()).isEqualTo("PAID");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Nhân viên hủy mã chưa trả thì khoản thu chuyển FAILED để thu lại bằng cách khác")
    void cancel_unpaidLink_closesPayment() {
        var resp = service.cancel(storedLink.getId());

        assertThat(resp.status()).isEqualTo("CANCELLED");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Hai yêu cầu cùng tạo link: yêu cầu bị cổng từ chối không đóng mã mà khách có thể đang quét")
    void start_duplicateLinkAtGateway_keepsAttemptOpen() {
        prepareUnattachedAttempt();
        when(gateway.getStatus(100001L)).thenReturn(new PaymentGateway.LinkStatus("PENDING", 0, null, null));

        assertThatThrownBy(() -> service.startAtCounter(order.getId(), storedPayment.getIdempotencyKey()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(MembershipResultCodes.ONLINE_PAYMENT_GATEWAY_ERROR));
        assertThat(storedLink.getStatus()).isEqualTo("CREATED");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Cổng từ chối tạo link và không giữ link nào thì đóng mã để thu lại")
    void start_rejectedLinkWithoutGatewayLink_closesAttempt() {
        prepareUnattachedAttempt();
        when(gateway.getStatus(100001L)).thenThrow(new PaymentGateway.GatewayException("PayOS code 101", true));

        assertThatThrownBy(() -> service.startAtCounter(order.getId(), storedPayment.getIdempotencyKey()))
                .isInstanceOf(BusinessException.class);
        assertThat(storedLink.getStatus()).isEqualTo("CANCELLED");
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Học viên không tạo được mã thanh toán cho đơn của người khác")
    void studentStart_otherCustomersOrder_isHidden() {
        signInStudent(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        when(payments.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startForStudent(order.getId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(MembershipResultCodes.ORDER_NOT_FOUND));
        verify(payments, never()).saveAndFlush(any());
        verify(gateway, never()).createLink(any());
    }

    @Test
    @DisplayName("Học viên tạo mã cho đơn của mình: link trả về trang của học viên, không phải trang quầy")
    void studentStart_ownOrder_usesStudentReturnUrl() {
        UUID student = UUID.randomUUID();
        signInStudent(student);
        order.setCustomerId(student);
        storedLink.setCheckoutUrl(null);
        storedLink.setCreatedAt(Instant.now());
        when(payments.findByIdempotencyKey(storedPayment.getIdempotencyKey())).thenAnswer(inv -> Optional.of(copyPayment(storedPayment)));
        when(links.findByPaymentId(storedPayment.getId())).thenAnswer(inv -> Optional.of(copyLink(storedLink)));
        when(gateway.createLink(any())).thenReturn(new PaymentGateway.CreatedLink("L1", "https://pay/1", "000201"));

        var resp = service.startForStudent(order.getId(), storedPayment.getIdempotencyKey());

        var request = ArgumentCaptor.forClass(PaymentGateway.LinkRequest.class);
        verify(gateway).createLink(request.capture());
        assertThat(request.getValue().returnUrl()).isEqualTo("https://app/plans");
        assertThat(resp.qrPayload()).isEqualTo("000201");
    }

    @Test
    @DisplayName("Học viên trả học phí khóa học: PayOS trả về trang khóa học")
    void studentStart_courseOrder_usesCourseReturnUrl() {
        UUID student = UUID.randomUUID();
        signInStudent(student);
        order.setCustomerId(student);
        var item = new com.company.yoga.membership.order.entity.OrderItemEntity();
        item.setItemType("COURSE_CLASS");
        when(orderItems.findByOrderId(order.getId())).thenReturn(List.of(item));
        storedLink.setCheckoutUrl(null);
        storedLink.setCreatedAt(Instant.now());
        when(payments.findByIdempotencyKey(storedPayment.getIdempotencyKey())).thenAnswer(inv -> Optional.of(copyPayment(storedPayment)));
        when(links.findByPaymentId(storedPayment.getId())).thenAnswer(inv -> Optional.of(copyLink(storedLink)));
        when(gateway.createLink(any())).thenReturn(new PaymentGateway.CreatedLink("L1", "https://pay/1", "000201"));

        service.startForStudent(order.getId(), storedPayment.getIdempotencyKey());

        var request = ArgumentCaptor.forClass(PaymentGateway.LinkRequest.class);
        verify(gateway).createLink(request.capture());
        assertThat(request.getValue().returnUrl()).isEqualTo("https://app/courses");
    }

    @Test
    @DisplayName("Học viên không xem hay hủy được mã thanh toán của người khác")
    void studentCancel_otherCustomersLink_isHidden() {
        signInStudent(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());

        assertThatThrownBy(() -> service.cancelForStudent(storedLink.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(MembershipResultCodes.ONLINE_PAYMENT_NOT_FOUND));
        assertThatThrownBy(() -> service.getForStudent(storedLink.getId())).isInstanceOf(BusinessException.class);
        verify(gateway, never()).cancel(anyLong(), anyString());
        assertThat(storedPayment.getPaymentStatus()).isEqualTo("PENDING");
    }

    private void signInStudent(UUID id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        when(policy.actor()).thenReturn(user);
        when(policy.role(user)).thenReturn("STUDENT");
    }

    private void prepareUnattachedAttempt() {
        storedLink.setCheckoutUrl(null);
        storedLink.setCreatedAt(Instant.now());
        when(payments.findByIdempotencyKey(storedPayment.getIdempotencyKey())).thenAnswer(inv -> Optional.of(copyPayment(storedPayment)));
        when(links.findByPaymentId(storedPayment.getId())).thenAnswer(inv -> Optional.of(copyLink(storedLink)));
        when(gateway.createLink(any())).thenThrow(new PaymentGateway.GatewayException("PayOS code 231", true));
    }

    private static PaymentGateway.PaidNotice notice(long amount) {
        return new PaymentGateway.PaidNotice("L1:FT1", 100001L, amount, "FT1", Instant.now(), true);
    }

    private static OnlinePaymentTransactionEntity copyLink(OnlinePaymentTransactionEntity source) {
        var target = new OnlinePaymentTransactionEntity();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static PaymentEntity copyPayment(PaymentEntity source) {
        var target = new PaymentEntity();
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
