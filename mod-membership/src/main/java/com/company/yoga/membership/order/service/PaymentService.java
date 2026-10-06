package com.company.yoga.membership.order.service;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.order.dto.PaymentDto;
import com.company.yoga.membership.order.entity.PaymentEntity;
import com.company.yoga.membership.order.repository.OrderItemRepository;
import com.company.yoga.membership.order.repository.OrderRepository;
import com.company.yoga.membership.order.repository.PaymentRepository;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final MembershipRepository membershipRepository;
    private final AccessPolicy accessPolicy;
    private final EntityManager entityManager;
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public PaymentDto.Resp processPayment(PaymentDto.PayReq req) {
        String method = req.paymentMethod().trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CASH", "POS_CARD", "BANK_TRANSFER_QR").contains(method)) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        // Serialize the key before reading it, also for requests against different orders.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 0))")
                .setParameter("key", req.idempotencyKey().toString()).getResultList();
        var order = orderRepository.findByIdWithLock(req.orderId())
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.ORDER_NOT_FOUND));
        UUID actor = accessPolicy.requireStaffBranch(order.getBranchId());
        var previous = paymentRepository.findByIdempotencyKey(req.idempotencyKey());
        if (previous.isPresent()) {
            PaymentEntity p = previous.get();
            if (!Objects.equals(p.getOrderId(), req.orderId()) || !method.equals(p.getPaymentMethod())
                    || !Objects.equals(p.getTransactionReference(), req.transactionReference()) || !Objects.equals(p.getCashierId(), actor))
                throw new BusinessException(CommonErrorCode.CONFLICT, "Idempotency key belongs to a different payment");
            return response(p);
        }
        if ("PAID".equals(order.getStatus())) throw new BusinessException(MembershipResultCodes.ORDER_ALREADY_PAID);
        if (!"PENDING".equals(order.getStatus())) throw new BusinessException(CommonErrorCode.CONFLICT);
        PaymentEntity payment = new PaymentEntity();
        payment.setPaymentCode("PAY-" + UUID.randomUUID());
        payment.setOrderId(order.getId()); payment.setBranchId(order.getBranchId()); payment.setAmount(order.getTotalAmount());
        payment.setPaymentMethod(method); payment.setPaymentStatus("PENDING"); payment.setIdempotencyKey(req.idempotencyKey());
        payment.setCashierId(actor); payment.setTransactionReference(req.transactionReference()); payment.setPaymentTime(Instant.now()); payment.setNotes(req.notes());
        payment = paymentRepository.saveAndFlush(payment);
        payment.setPaymentStatus("SUCCESS"); payment.setConfirmedBy(actor); payment.setConfirmedAt(Instant.now());
        payment = paymentRepository.saveAndFlush(payment);
        // Database apply_payment owns order status and membership activation.
        return response(payment);
    }
    private PaymentDto.Resp response(PaymentEntity p) {
        String code = orderItemRepository.findByOrderId(p.getOrderId()).stream().filter(i -> "MEMBERSHIP_PLAN".equals(i.getItemType()))
                .map(i -> membershipRepository.findBySourceOrderItemId(i.getId())).flatMap(java.util.Optional::stream)
                .map(m -> m.getMembershipCode()).findFirst().orElse(null);
        return new PaymentDto.Resp(p.getId(), p.getPaymentCode(), p.getOrderId(), p.getAmount(), p.getPaymentMethod(), p.getPaymentStatus(), p.getPaymentTime(), code);
    }
}
