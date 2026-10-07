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
    private final com.company.yoga.membership.order.repository.PaymentRepository paymentRepository;
    private final com.company.yoga.branch.facility.repository.BranchRepository branchRepository;

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
            if (!java.util.Objects.equals(membership.getReplacesMembershipId(), req.replacesMembershipId())
                    || req.expectedCredit() == null || membership.getExchangeBaseCredit().compareTo(req.expectedCredit()) != 0
                    || (req.expectedTotal() != null && order.getTotalAmount().compareTo(req.expectedTotal()) != 0))
                throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Order key belongs to another exchange");
            if (req.adjustedCredit() != null && membership.getExchangeCredit().compareTo(req.adjustedCredit()) != 0
                    || !java.util.Objects.equals(membership.getExchangeAdjustmentReason(), req.adjustmentReason()))
                throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Order key belongs to another adjustment");
            return new PosOrderDto.OrderResp(order.getId(), order.getOrderCode(), order.getBranchId(), order.getCustomerId(), item.getItemId(), item.getItemNameSnapshot(), order.getSubtotal(), order.getDiscountAmount(), order.getTotalAmount(), order.getStatus(), order.getOrderDate(), membership.getId(), membership.getMembershipCode());
        }
        MembershipPlanEntity plan = planRepository.findById(req.planId())
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND));

        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND);
        }

        var quote = getCheckoutQuote(req.studentId(), req.planId(), req.branchId());
        UUID currentId = quote.currentMembership() == null ? null : quote.currentMembership().id();
        if (quote.issue() != null) throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, quote.issue());
        BigDecimal credit = quote.credit();
        UUID adjustedBy = null;
        if (req.adjustedCredit() != null) {
            String role = accessPolicy.role(accessPolicy.actor());
            if (!java.util.Set.of("SUPER_ADMIN", "BRANCH_MANAGER").contains(role))
                throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.FORBIDDEN);
            if (currentId == null || req.adjustmentReason() == null || req.adjustmentReason().isBlank()
                    || req.adjustedCredit().stripTrailingZeros().scale() > 0 || req.adjustedCredit().signum() < 0 || req.adjustedCredit().compareTo(plan.getPrice()) > 0
                    || req.adjustedCredit().compareTo(quote.contractValue()) > 0)
                throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST, "Nhập lý do và giá trị điều chỉnh hợp lệ, không vượt giá trị đã mua hoặc giá gói mới.");
            credit = req.adjustedCredit().setScale(0, java.math.RoundingMode.DOWN);
            adjustedBy = cashierId;
        } else if (req.adjustmentReason() != null) {
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST, "Lý do điều chỉnh cần đi kèm giá trị điều chỉnh.");
        }
        BigDecimal totalAmount = plan.getPrice().subtract(credit);
        if (!java.util.Objects.equals(currentId, req.replacesMembershipId()) || req.expectedCredit() == null
                || req.expectedCredit().compareTo(quote.credit()) != 0
                || (req.expectedTotal() != null && req.expectedTotal().compareTo(totalAmount) != 0))
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Thông tin thẻ hoặc số tiền đã thay đổi. Vui lòng tải lại báo giá.");

        // 1. Tạo đơn hàng (Order)
        String orderCode = "ORD-" + key;
        OrderEntity order = new OrderEntity();
        order.setOrderCode(orderCode);
        order.setBranchId(req.branchId());
        order.setCustomerId(req.studentId());
        order.setCashierId(cashierId);
        order.setOrderDate(Instant.now());
        order.setSubtotal(plan.getPrice());
        order.setDiscountAmount(credit);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotalAmount(totalAmount);
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
        membership.setReplacesMembershipId(currentId);
        membership.setExchangeCredit(credit);
        membership.setExchangeBaseCredit(quote.credit());
        membership.setExchangeAdjustedBy(adjustedBy);
        membership.setExchangeAdjustmentReason(req.adjustmentReason());
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

    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public PosOrderDto.CheckoutQuote getCheckoutQuote(UUID studentId, UUID planId, UUID branchId) {
        accessPolicy.requireStaffBranch(branchId);
        accessPolicy.requireBooking(studentId, branchId);
        var plan = planRepository.findById(planId).orElseThrow(() -> new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND));
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery("SELECT current_id, credit, issue, contract_value FROM yoga.membership_exchange_quote(:student,:plan,:branch,NULL)")
                    .setParameter("student", studentId).setParameter("plan", planId).setParameter("branch", branchId).getSingleResult();
        } catch (RuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null && java.util.Set.of("42883", "42703", "42P01").contains(sql.getSQLState()))
                    throw new BusinessException(MembershipResultCodes.CHECKOUT_NOT_READY);
            }
            throw error;
        }
        UUID currentId = row[0] == null ? null : UUID.fromString(row[0].toString());
        var current = currentId == null ? null : membershipRepository.findById(currentId)
                .map(com.company.yoga.membership.plan.dto.MembershipDto.Resp::from).orElseThrow();
        BigDecimal credit = (BigDecimal) row[1];
        String currentPlanName = current == null ? null : planRepository.findById(current.planId()).map(MembershipPlanEntity::getName).orElse("Gói ngừng bán");
        return new PosOrderDto.CheckoutQuote(current, currentPlanName, (BigDecimal) row[3], credit, plan.getPrice().subtract(credit), (String) row[2]);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public void cancelPendingOrder(UUID orderId) {
        var order = orderRepository.findByIdWithLock(orderId).orElseThrow(() -> new BusinessException(MembershipResultCodes.ORDER_NOT_FOUND));
        accessPolicy.requireStaffBranch(order.getBranchId());
        entityManager.createNativeQuery("SELECT 1 FROM yoga.cancel_pending_membership_order(:id)").setParameter("id", orderId).getSingleResult();
    }

    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST', 'STUDENT')")
    public java.util.List<PosOrderDto.StudentOrderHistoryResp> getStudentOrders(UUID studentId) {
        accessPolicy.requireStudent(studentId);
        java.util.List<OrderEntity> orders = orderRepository.findByCustomerIdOrderByOrderDateDesc(studentId);
        if (orders.isEmpty()) {
            return java.util.List.of();
        }

        java.util.Map<UUID, String> branchNames = branchRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.company.yoga.branch.facility.entity.BranchEntity::getId,
                        com.company.yoga.branch.facility.entity.BranchEntity::getName
                ));

        return orders.stream().map(order -> {
            java.util.List<OrderItemEntity> items = orderItemRepository.findByOrderId(order.getId());
            java.util.List<com.company.yoga.membership.order.entity.PaymentEntity> payments = paymentRepository.findByOrderId(order.getId());

            java.util.List<PosOrderDto.OrderItemResp> itemDtos = items.stream()
                    .map(item -> {
                        var mbOpt = membershipRepository.findBySourceOrderItemId(item.getId());
                        String mbCode = mbOpt.map(MembershipEntity::getMembershipCode).orElse(null);
                        String mbStatus = mbOpt.map(MembershipEntity::getStatus).orElse(null);
                        Integer remaining = mbOpt.map(MembershipEntity::getRemainingSessions).orElse(null);
                        Integer total = mbOpt.map(MembershipEntity::getTotalSessions).orElse(null);
                        java.time.LocalDate sDate = mbOpt.map(MembershipEntity::getStartDate).orElse(null);
                        java.time.LocalDate eDate = mbOpt.map(MembershipEntity::getEndDate).orElse(null);
                        return new PosOrderDto.OrderItemResp(
                                item.getItemId(),
                                item.getItemType(),
                                item.getItemNameSnapshot(),
                                item.getUnitPriceSnapshot(),
                                item.getQuantity(),
                                item.getLineTotal(),
                                mbCode,
                                mbStatus,
                                remaining,
                                total,
                                sDate,
                                eDate
                        );
                    })
                    .toList();

            java.util.List<PosOrderDto.PaymentResp> paymentDtos = payments.stream()
                    .map(p -> new PosOrderDto.PaymentResp(
                            p.getId(),
                            p.getPaymentCode(),
                            p.getAmount(),
                            p.getPaymentMethod(),
                            p.getPaymentStatus(),
                            p.getPaymentTime()
                    ))
                    .toList();

            String branchName = order.getBranchId() != null ? branchNames.get(order.getBranchId()) : null;

            return new PosOrderDto.StudentOrderHistoryResp(
                    order.getId(),
                    order.getOrderCode(),
                    order.getBranchId(),
                    branchName,
                    order.getOrderDate(),
                    order.getSubtotal(),
                    order.getDiscountAmount(),
                    order.getTotalAmount(),
                    order.getStatus(),
                    order.getNotes(),
                    itemDtos,
                    paymentDtos
            );
        }).toList();
    }
}
