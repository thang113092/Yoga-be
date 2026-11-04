package com.company.yoga.membership.payment.online;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.MembershipResultCodes;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money the gateway received that could not activate its order. Only managers settle it (refund, manual
 * activation, ...) and record how; until then the order can be neither cancelled nor collected again.
 */
@Service
@RequiredArgsConstructor
public class OnlinePaymentReconciliationService {

    private static final int MAX_NOTE = 1000;

    private final OnlinePaymentTransactionRepository links;
    private final AccessPolicy accessPolicy;
    private final UserBranchRepository assignments;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public List<OnlinePaymentDto.ReconciliationRow> pending(UUID branchId) {
        var actor = accessPolicy.actor();
        String role = accessPolicy.role(actor);
        if (!Set.of("SUPER_ADMIN", "BRANCH_MANAGER").contains(role)) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        // null = every branch (super admin without a filter); otherwise only these branches.
        List<UUID> scope = branchId == null ? null : List.of(branchId);
        if ("BRANCH_MANAGER".equals(role)) {
            Set<UUID> allowed = new HashSet<>(assignments.branchIdsForUser(actor.getId()));
            if (actor.getHomeBranchId() != null) allowed.add(actor.getHomeBranchId());
            if (branchId != null && !allowed.contains(branchId)) throw new BusinessException(CommonErrorCode.FORBIDDEN);
            if (allowed.isEmpty()) return List.of();
            if (scope == null) scope = new ArrayList<>(allowed);
        }
        var query = entityManager.createNativeQuery("""
                SELECT t.id, o.id, o.order_code, b.id, b.name, u.full_name, u.phone, t.provider_order_code,
                       t.amount, t.paid_amount, t.paid_at, t.provider_reference, o.status, t.created_at
                FROM yoga.online_payment_transactions t
                JOIN yoga.payments p ON p.id=t.payment_id
                JOIN yoga.orders o ON o.id=p.order_id
                JOIN yoga.branches b ON b.id=t.branch_id
                LEFT JOIN yoga.users u ON u.id=o.customer_id
                WHERE t.status='NEEDS_RECONCILIATION'
                """ + (scope == null ? "" : " AND t.branch_id IN (:scope)") + """

                ORDER BY t.paid_at NULLS LAST, t.created_at
                LIMIT 200
                """);
        if (scope != null) query.setParameter("scope", scope);
        List<?> rows = query.getResultList();
        return rows.stream().map(r -> {
            Object[] c = (Object[]) r;
            return new OnlinePaymentDto.ReconciliationRow((UUID) c[0], (UUID) c[1], (String) c[2], (UUID) c[3], (String) c[4],
                    (String) c[5], (String) c[6], ((Number) c[7]).longValue(), (BigDecimal) c[8], (BigDecimal) c[9],
                    instant(c[10]), (String) c[11], (String) c[12], instant(c[13]));
        }).toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public void resolve(UUID id, String note) {
        String text = note == null ? "" : note.trim();
        if (text.isEmpty() || text.length() > MAX_NOTE) throw new BusinessException(CommonErrorCode.BAD_REQUEST, "Nhập cách xử lý (tối đa 1000 ký tự).");
        var actor = accessPolicy.actor();
        if (!Set.of("SUPER_ADMIN", "BRANCH_MANAGER").contains(accessPolicy.role(actor))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        OnlinePaymentTransactionEntity link = links.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.ONLINE_PAYMENT_NOT_FOUND));
        UUID resolver = accessPolicy.requireStaffBranch(link.getBranchId());
        if (!"NEEDS_RECONCILIATION".equals(link.getStatus()))
            throw new BusinessException(CommonErrorCode.CONFLICT, "Giao dịch không còn chờ đối soát.");
        entityManager.createNativeQuery("SELECT set_config('app.actor_id', :actor, true)")
                .setParameter("actor", resolver.toString()).getResultList();
        link.setStatus("RESOLVED");
        link.setResolvedBy(resolver);
        link.setResolutionNote(text);
        links.saveAndFlush(link);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
    }
}
