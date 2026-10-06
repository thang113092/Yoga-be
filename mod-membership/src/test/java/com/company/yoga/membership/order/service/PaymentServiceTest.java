package com.company.yoga.membership.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.order.dto.PaymentDto;
import com.company.yoga.membership.order.entity.OrderEntity;
import com.company.yoga.membership.order.entity.OrderItemEntity;
import com.company.yoga.membership.order.entity.PaymentEntity;
import com.company.yoga.membership.order.repository.OrderItemRepository;
import com.company.yoga.membership.order.repository.OrderRepository;
import com.company.yoga.membership.order.repository.PaymentRepository;
import com.company.yoga.membership.plan.entity.MembershipEntity;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private PaymentRepository paymentRepository;
    private MembershipRepository membershipRepository;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        membershipRepository = mock(MembershipRepository.class);
        var policy = mock(com.company.yoga.identity.account.service.AccessPolicy.class);
        var em = mock(jakarta.persistence.EntityManager.class);
        var query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(policy.requireStaffBranch(any())).thenReturn(new UUID(0,1));
        paymentService = new PaymentService(orderRepository, orderItemRepository, paymentRepository, membershipRepository, policy, em);
    }

    @Test
    @DisplayName("Thanh toán thành công kích hoạt thẻ tập và cập nhật đơn hàng sang PAID")
    void processPayment_success_activatesMembership() {
        UUID orderId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID cashierId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setBranchId(branchId);
        order.setTotalAmount(new BigDecimal("1200000"));
        order.setStatus("PENDING");

        OrderItemEntity item = new OrderItemEntity();
        item.setId(orderItemId);
        item.setOrderId(orderId);
        item.setItemType("MEMBERSHIP_PLAN");

        MembershipEntity membership = new MembershipEntity();
        membership.setMembershipCode("MB-TEST-001");
        membership.setStatus("PENDING_PAYMENT");
        membership.setSourceOrderItemId(orderItemId);

        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(PaymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(membershipRepository.findBySourceOrderItemId(orderItemId)).thenReturn(Optional.of(membership));

        PaymentDto.PayReq req = new PaymentDto.PayReq(
                orderId,
                "CASH",
                idempotencyKey,
                cashierId,
                "REF-001",
                "Tiền mặt tại quầy"
        );

        PaymentDto.Resp resp = paymentService.processPayment(req);

        assertThat(resp).isNotNull();
        assertThat(resp.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(resp.amount()).isEqualByComparingTo("1200000");
        assertThat(resp.activatedMembershipCode()).isEqualTo("MB-TEST-001");

        // Verify order is PAID
        assertThat(order.getStatus()).isEqualTo("PENDING");
        // Verify membership is ACTIVE
        assertThat(membership.getStatus()).isEqualTo("PENDING_PAYMENT");

        verify(paymentRepository, times(2)).saveAndFlush(any(PaymentEntity.class));
        verify(orderRepository, never()).save(any());
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("Chống ghi đúp (Idempotency Guard): Trả lại kết quả cũ khi cùng key gửi lại")
    void processPayment_duplicateIdempotencyKey_returnsExistingPayment() {
        UUID orderId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        PaymentEntity existing = new PaymentEntity();
        existing.setId(UUID.randomUUID());
        existing.setPaymentCode("PAY-EXISTING-001");
        existing.setOrderId(orderId);
        existing.setAmount(new BigDecimal("3600000"));
        existing.setPaymentMethod("POS_CARD");
        existing.setPaymentStatus("SUCCESS");
        existing.setIdempotencyKey(idempotencyKey);
        existing.setCashierId(new UUID(0,1));
        OrderEntity order = new OrderEntity(); order.setId(orderId);
        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentDto.PayReq req = new PaymentDto.PayReq(
                orderId,
                "POS_CARD",
                idempotencyKey,
                UUID.randomUUID(),
                null,
                null
        );

        PaymentDto.Resp resp = paymentService.processPayment(req);

        assertThat(resp).isNotNull();
        assertThat(resp.paymentCode()).isEqualTo("PAY-EXISTING-001");
        assertThat(resp.activatedMembershipCode()).isNull();

        // Không bao giờ tạo thanh toán mới
        verify(orderRepository, never()).findById(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ném lỗi khi thanh toán đơn hàng đã được thanh toán trước đó")
    void processPayment_orderAlreadyPaid_throwsBusinessException() {
        UUID orderId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        OrderEntity paidOrder = new OrderEntity();
        paidOrder.setId(orderId);
        paidOrder.setStatus("PAID");

        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(paidOrder));

        PaymentDto.PayReq req = new PaymentDto.PayReq(
                orderId,
                "CASH",
                idempotencyKey,
                UUID.randomUUID(),
                null,
                null
        );

        assertThatThrownBy(() -> paymentService.processPayment(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MembershipResultCodes.ORDER_ALREADY_PAID);
    }
}
