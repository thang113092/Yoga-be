package com.company.yoga.membership.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.order.dto.PosOrderDto;
import com.company.yoga.membership.order.entity.OrderEntity;
import com.company.yoga.membership.order.entity.OrderItemEntity;
import com.company.yoga.membership.order.repository.OrderItemRepository;
import com.company.yoga.membership.order.repository.OrderRepository;
import com.company.yoga.membership.plan.entity.MembershipEntity;
import com.company.yoga.membership.plan.entity.MembershipPlanEntity;
import com.company.yoga.membership.plan.repository.MembershipPlanRepository;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PosOrderServiceTest {

    private MembershipPlanRepository planRepository;
    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private MembershipRepository membershipRepository;
    private com.company.yoga.membership.order.repository.PaymentRepository paymentRepository;
    private com.company.yoga.branch.facility.repository.BranchRepository branchRepository;
    private PosOrderService service;
    private jakarta.persistence.Query query;
    private com.company.yoga.identity.account.service.AccessPolicy policy;

    @BeforeEach
    void setUp() {
        planRepository = mock(MembershipPlanRepository.class);
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        membershipRepository = mock(MembershipRepository.class);
        paymentRepository = mock(com.company.yoga.membership.order.repository.PaymentRepository.class);
        branchRepository = mock(com.company.yoga.branch.facility.repository.BranchRepository.class);
        policy = mock(com.company.yoga.identity.account.service.AccessPolicy.class);
        var em = mock(jakarta.persistence.EntityManager.class);
        query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(query.getSingleResult()).thenReturn(new Object[]{null, BigDecimal.ZERO, null, BigDecimal.ZERO});
        when(policy.requireStaffBranch(any())).thenReturn(new UUID(0,1));
        service = new PosOrderService(policy, em, planRepository, orderRepository, orderItemRepository, membershipRepository, paymentRepository, branchRepository);
    }

    @Test
    @DisplayName("Tạo đơn hàng POS bán thẻ thành công, snapshot đúng giá và sinh thẻ PENDING_PAYMENT")
    void createMembershipOrder_success() {
        UUID planId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID cashierId = UUID.randomUUID();

        MembershipPlanEntity plan = new MembershipPlanEntity();
        plan.setId(planId);
        plan.setName("Thẻ 10 Buổi Khởi Tâm");
        plan.setPrice(new BigDecimal("1200000"));
        plan.setPlanType("SESSION_BASED");
        plan.setTotalSessions(10);
        plan.setIsAllBranches(false);
        plan.setIsActive(true);

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        PosOrderDto.CreateOrderReq req = new PosOrderDto.CreateOrderReq(
                branchId,
                studentId,
                planId,
                cashierId,
                "Bán thẻ tại quầy cơ sở Thảo Điền"
        );

        PosOrderDto.OrderResp resp = service.createMembershipOrder(req);

        assertThat(resp).isNotNull();
        assertThat(resp.planName()).isEqualTo("Thẻ 10 Buổi Khởi Tâm");
        assertThat(resp.totalAmount()).isEqualByComparingTo("1200000");
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(resp.membershipCode()).startsWith("MB-");

        verify(orderRepository, times(1)).save(any(OrderEntity.class));
        verify(orderItemRepository, times(1)).save(any(OrderItemEntity.class));
        verify(membershipRepository, times(1)).save(any(MembershipEntity.class));
    }

    @Test
    @DisplayName("Ném lỗi khi gói tập không tồn tại hoặc đã ngừng hoạt động")
    void createMembershipOrder_inactivePlan_throwsBusinessException() {
        UUID planId = UUID.randomUUID();
        MembershipPlanEntity inactivePlan = new MembershipPlanEntity();
        inactivePlan.setId(planId);
        inactivePlan.setIsActive(false);

        when(planRepository.findById(planId)).thenReturn(Optional.of(inactivePlan));

        PosOrderDto.CreateOrderReq req = new PosOrderDto.CreateOrderReq(
                UUID.randomUUID(),
                UUID.randomUUID(),
                planId,
                UUID.randomUUID(),
                null
        );

        assertThatThrownBy(() -> service.createMembershipOrder(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MembershipResultCodes.PLAN_NOT_FOUND);
    }

    @Test
    @DisplayName("Lấy danh sách lịch sử đơn hàng của học viên thành công")
    void getStudentOrders_success() {
        UUID studentId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setOrderCode("ORD-12345");
        order.setCustomerId(studentId);
        order.setBranchId(branchId);
        order.setSubtotal(new BigDecimal("1000000"));
        order.setTotalAmount(new BigDecimal("1000000"));
        order.setStatus("PAID");

        when(orderRepository.findByCustomerIdOrderByOrderDateDesc(studentId)).thenReturn(List.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());
        when(paymentRepository.findByOrderId(order.getId())).thenReturn(List.of());
        when(branchRepository.findAll()).thenReturn(List.of());

        List<PosOrderDto.StudentOrderHistoryResp> res = service.getStudentOrders(studentId);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).orderCode()).isEqualTo("ORD-12345");
        assertThat(res.get(0).status()).isEqualTo("PAID");
    }

    private UUID exchangeFixture(String issue) {
        UUID oldId = UUID.randomUUID();
        MembershipEntity old = new MembershipEntity();
        old.setId(oldId); old.setPlanId(UUID.randomUUID()); old.setPurchasedPrice(new BigDecimal("2000000"));
        when(membershipRepository.findById(oldId)).thenReturn(Optional.of(old));
        when(query.getSingleResult()).thenReturn(new Object[]{oldId, new BigDecimal("800000"), issue, new BigDecimal("2000000")});
        return oldId;
    }

    private UUID newPlan() {
        UUID id = UUID.randomUUID();
        MembershipPlanEntity plan = new MembershipPlanEntity();
        plan.setId(id); plan.setName("30 buổi"); plan.setPrice(new BigDecimal("3000000"));
        plan.setTotalSessions(30); plan.setDurationDays(90); plan.setIsActive(true);
        when(planRepository.findById(id)).thenReturn(Optional.of(plan));
        return id;
    }

    @Test
    void existingMembershipRequiresExplicitExchange() {
        UUID plan = newPlan(); exchangeFixture(null);
        assertThatThrownBy(() -> service.createMembershipOrder(new PosOrderDto.CreateOrderReq(UUID.randomUUID(), UUID.randomUUID(), plan, UUID.randomUUID(), null)))
                .isInstanceOf(BusinessException.class);
        verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void exchangeCreditsUnusedValueAndLinksOldMembership() {
        UUID plan = newPlan(); UUID old = exchangeFixture(null);
        var response = service.createMembershipOrder(new PosOrderDto.CreateOrderReq(UUID.randomUUID(), UUID.randomUUID(), plan, UUID.randomUUID(), null,
                old, new BigDecimal("800000"), new BigDecimal("2200000"), null, null));
        assertThat(response.totalAmount()).isEqualByComparingTo("2200000");
        assertThat(response.discountAmount()).isEqualByComparingTo("800000");
        var saved = org.mockito.ArgumentCaptor.forClass(MembershipEntity.class);
        verify(membershipRepository).save(saved.capture());
        assertThat(saved.getValue().getReplacesMembershipId()).isEqualTo(old);
        assertThat(saved.getValue().getExchangeBaseCredit()).isEqualByComparingTo("800000");
    }

    @Test
    void staleQuoteIsRejectedBeforeCreatingOrder() {
        UUID plan = newPlan(); UUID old = exchangeFixture(null);
        assertThatThrownBy(() -> service.createMembershipOrder(new PosOrderDto.CreateOrderReq(UUID.randomUUID(), UUID.randomUUID(), plan, UUID.randomUUID(), null,
                old, new BigDecimal("900000"), new BigDecimal("2100000"), null, null))).isInstanceOf(BusinessException.class);
        verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void pendingOrFrozenMembershipBlocksOrder() {
        UUID plan = newPlan(); UUID old = exchangeFixture("Cần kết thúc bảo lưu");
        assertThatThrownBy(() -> service.createMembershipOrder(new PosOrderDto.CreateOrderReq(UUID.randomUUID(), UUID.randomUUID(), plan, UUID.randomUUID(), null,
                old, new BigDecimal("800000"), new BigDecimal("2200000"), null, null))).isInstanceOf(BusinessException.class);
        verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void receptionistCannotOverrideCredit() {
        UUID plan = newPlan(); UUID old = exchangeFixture(null);
        when(policy.role(any())).thenReturn("RECEPTIONIST");
        assertThatThrownBy(() -> service.createMembershipOrder(new PosOrderDto.CreateOrderReq(UUID.randomUUID(), UUID.randomUUID(), plan, UUID.randomUUID(), null,
                old, new BigDecimal("800000"), new BigDecimal("2100000"), new BigDecimal("900000"), "Bảo toàn quyền lợi"))).isInstanceOf(BusinessException.class);
        verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void managerOverrideRecordsReasonActorAndFormulaValue() {
        UUID plan = newPlan(); UUID old = exchangeFixture(null);
        when(policy.role(any())).thenReturn("BRANCH_MANAGER");
        var response = service.createMembershipOrder(new PosOrderDto.CreateOrderReq(UUID.randomUUID(), UUID.randomUUID(), plan, UUID.randomUUID(), null,
                old, new BigDecimal("800000"), new BigDecimal("2100000"), new BigDecimal("900000"), "Bảo toàn quyền lợi"));
        assertThat(response.totalAmount()).isEqualByComparingTo("2100000");
        var saved = org.mockito.ArgumentCaptor.forClass(MembershipEntity.class);
        verify(membershipRepository).save(saved.capture());
        assertThat(saved.getValue().getExchangeAdjustedBy()).isEqualTo(new UUID(0,1));
        assertThat(saved.getValue().getExchangeAdjustmentReason()).isEqualTo("Bảo toàn quyền lợi");
        assertThat(saved.getValue().getExchangeBaseCredit()).isEqualByComparingTo("800000");
    }

    @Test
    void missingQuoteFunctionReturnsActionableServiceUnavailable() {
        UUID plan = newPlan();
        when(query.getSingleResult()).thenThrow(new RuntimeException(new java.sql.SQLException("function membership_exchange_quote does not exist", "42883")));
        assertThatThrownBy(() -> service.getCheckoutQuote(UUID.randomUUID(), plan, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MembershipResultCodes.CHECKOUT_NOT_READY);
    }

    @Test
    void unrelatedDatabaseFailureIsNotMaskedAsMigrationProblem() {
        UUID plan = newPlan();
        var failure = new RuntimeException(new java.sql.SQLException("connection unavailable", "08006"));
        when(query.getSingleResult()).thenThrow(failure);
        assertThatThrownBy(() -> service.getCheckoutQuote(UUID.randomUUID(), plan, UUID.randomUUID())).isSameAs(failure);
    }

    @Test
    @DisplayName("Học viên tự mua: chính học viên là khách và người lập đơn, có ghi chú mua online")
    void createSelfServiceOrder_usesCallerAsCustomerAndCashier() {
        UUID me = UUID.randomUUID(), planId = UUID.randomUUID(), branchId = UUID.randomUUID();
        signInStudent(me);
        activeBranch(branchId);
        MembershipPlanEntity plan = new MembershipPlanEntity();
        plan.setId(planId); plan.setName("Thẻ 1 tháng"); plan.setPrice(new BigDecimal("900000"));
        plan.setDurationDays(30); plan.setIsAllBranches(false); plan.setIsActive(true);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        var saved = org.mockito.ArgumentCaptor.forClass(OrderEntity.class);

        var resp = service.createSelfServiceOrder(new PosOrderDto.SelfServiceOrderReq(branchId, planId, null, BigDecimal.ZERO, new BigDecimal("900000")), UUID.randomUUID());

        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo(me);
        assertThat(saved.getValue().getCashierId()).isEqualTo(me);
        assertThat(saved.getValue().getNotes()).isEqualTo(PosOrderService.SELF_SERVICE_NOTE);
        assertThat(resp.totalAmount()).isEqualByComparingTo("900000");
        verify(policy, org.mockito.Mockito.never()).requireStaffBranch(any());
    }

    @Test
    @DisplayName("Học viên tự mua: số tiền khác báo giá thì từ chối để học viên tải lại")
    void createSelfServiceOrder_staleQuote_isRejected() {
        UUID planId = UUID.randomUUID(), branchId = UUID.randomUUID();
        signInStudent(UUID.randomUUID());
        activeBranch(branchId);
        MembershipPlanEntity plan = new MembershipPlanEntity();
        plan.setId(planId); plan.setName("Thẻ 1 tháng"); plan.setPrice(new BigDecimal("900000")); plan.setIsActive(true);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.createSelfServiceOrder(new PosOrderDto.SelfServiceOrderReq(branchId, planId, null, BigDecimal.ZERO, new BigDecimal("800000")), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verify(orderRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("Học viên không mua được tại cơ sở đã ngừng hoạt động")
    void selfServiceQuote_inactiveBranch_isRejected() {
        signInStudent(UUID.randomUUID());
        UUID branchId = UUID.randomUUID();
        var branch = new com.company.yoga.branch.facility.entity.BranchEntity();
        branch.setIsActive(false);
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));

        assertThatThrownBy(() -> service.getSelfServiceQuote(UUID.randomUUID(), branchId)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Học viên không hủy được đơn của người khác")
    void cancelOwnPendingOrder_otherCustomer_isHidden() {
        signInStudent(UUID.randomUUID());
        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID()); order.setCustomerId(UUID.randomUUID()); order.setStatus("PENDING");
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOwnPendingOrder(order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MembershipResultCodes.ORDER_NOT_FOUND);
        verify(query, org.mockito.Mockito.never()).getSingleResult();
    }

    private void signInStudent(UUID id) {
        var user = new com.company.yoga.identity.account.entity.UserEntity();
        user.setId(id);
        when(policy.actor()).thenReturn(user);
        when(policy.role(user)).thenReturn("STUDENT");
    }

    private void activeBranch(UUID id) {
        var branch = new com.company.yoga.branch.facility.entity.BranchEntity();
        branch.setIsActive(true);
        when(branchRepository.findById(id)).thenReturn(Optional.of(branch));
    }
}
