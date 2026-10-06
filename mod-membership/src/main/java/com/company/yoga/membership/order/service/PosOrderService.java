package com.company.yoga.membership.order.service;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.company.yoga.identity.account.service.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PosOrderService {

    private final AccessPolicy accessPolicy;
    private final jakarta.persistence.EntityManager entityManager;
    private final MembershipPlanRepository planRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MembershipRepository membershipRepository;

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public PosOrderDto.OrderResp createMembershipOrder(PosOrderDto.CreateOrderReq req) {
        return createMembershipOrder(req, UUID.randomUUID());
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public PosOrderDto.OrderResp createMembershipOrder(PosOrderDto.CreateOrderReq req, UUID key) {
        UUID cashierId = accessPolicy.requireStaffBranch(req.branchId());
        accessPolicy.requireBooking(req.studentId(), req.branchId());
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 1))")
                .setParameter("key", key.toString()).getResultList();
        var existing = orderRepository.findByOrderCode("ORD-" + key);
        if (existing.isPresent()) {
            var order = existing.get();
            var item = orderItemRepository.findByOrderId(order.getId()).stream().findFirst().orElseThrow();
            if (!java.util.Objects.equals(order.getCustomerId(), req.studentId()) || !java.util.Objects.equals(order.getBranchId(), req.branchId())
                    || !java.util.Objects.equals(order.getCashierId(), cashierId) || !java.util.Objects.equals(item.getItemId(), req.planId())
                    || !java.util.Objects.equals(order.getNotes(), req.notes()))
                throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Order key belongs to another checkout");
            var membership = membershipRepository.findBySourceOrderItemId(item.getId()).orElseThrow();
            return new PosOrderDto.OrderResp(order.getId(), order.getOrderCode(), order.getBranchId(), order.getCustomerId(), item.getItemId(), item.getItemNameSnapshot(), order.getSubtotal(), order.getDiscountAmount(), order.getTotalAmount(), order.getStatus(), order.getOrderDate(), membership.getId(), membership.getMembershipCode());
        }
        MembershipPlanEntity plan = planRepository.findById(req.planId())
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND));

        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND);
        }

        // 1. Tạo đơn hàng (Order)
        String orderCode = "ORD-" + key;
        OrderEntity order = new OrderEntity();
        order.setOrderCode(orderCode);
        order.setBranchId(req.branchId());
        order.setCustomerId(req.studentId());
        order.setCashierId(cashierId);
        order.setOrderDate(Instant.now());
        order.setSubtotal(plan.getPrice());
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotalAmount(plan.getPrice());
        order.setStatus("PENDING");
        order.setNotes(req.notes());
        orderRepository.save(order);

        // 2. Tạo dòng hàng hóa (OrderItem - Snapshot Pattern)
        OrderItemEntity item = new OrderItemEntity();
        item.setOrderId(order.getId());
        item.setItemType("MEMBERSHIP_PLAN");
        item.setItemId(plan.getId());
        item.setItemNameSnapshot(plan.getName());
        item.setUnitPriceSnapshot(plan.getPrice());
        item.setQuantity(1);
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setLineTotal(plan.getPrice());
        orderItemRepository.save(item);

        // 3. Tạo hợp đồng thẻ tập chờ kích hoạt (Membership - PENDING_PAYMENT)
        String membershipCode = "MB-" + java.util.UUID.randomUUID();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        LocalDate endDate = null;
        if (plan.getDurationDays() != null && plan.getDurationDays() > 0) {
            endDate = today.plusDays(plan.getDurationDays() - 1);
        }

        MembershipEntity membership = new MembershipEntity();
        membership.setMembershipCode(membershipCode);
        membership.setStudentId(req.studentId());
        membership.setPlanId(plan.getId());
        membership.setRegisteredBranchId(req.branchId());
        membership.setIsAllBranches(Boolean.TRUE.equals(plan.getIsAllBranches()));
        membership.setStartDate(today);
        membership.setEndDate(endDate);
        membership.setTotalSessions(plan.getTotalSessions());
        membership.setRemainingSessions(plan.getTotalSessions());
        membership.setStatus("PENDING_PAYMENT");
        membership.setSourceOrderItemId(item.getId());
        membership.setPurchasedPrice(plan.getPrice());
        membership.setNotes(req.notes());
        membershipRepository.save(membership);

        return new PosOrderDto.OrderResp(
                order.getId(),
                order.getOrderCode(),
                order.getBranchId(),
                order.getCustomerId(),
                plan.getId(),
                plan.getName(),
                order.getSubtotal(),
                order.getDiscountAmount(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getOrderDate(),
                membership.getId(),
                membership.getMembershipCode()
        );
    }
}
