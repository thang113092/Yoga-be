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
    private PosOrderService service;

    @BeforeEach
    void setUp() {
        planRepository = mock(MembershipPlanRepository.class);
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        membershipRepository = mock(MembershipRepository.class);
        var policy = mock(com.company.yoga.identity.account.service.AccessPolicy.class);
        var em = mock(jakarta.persistence.EntityManager.class);
        var query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(policy.requireStaffBranch(any())).thenReturn(new UUID(0,1));
        service = new PosOrderService(policy, em, planRepository, orderRepository, orderItemRepository, membershipRepository);
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
}
