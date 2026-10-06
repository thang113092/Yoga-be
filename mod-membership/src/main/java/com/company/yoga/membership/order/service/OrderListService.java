package com.company.yoga.membership.order.service;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.order.entity.OrderEntity;
import com.company.yoga.membership.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderListService {
    private final AccessPolicy policy;
    private final UserBranchRepository assignments;
    private final OrderRepository orders;
    private final UserRepository users;
    private final BranchRepository branches;

    public record Row(UUID id, String orderCode, UUID branchId, String branchName,
                      UUID customerId, String customerName, String customerPhone, String customerEmail,
                      Instant orderDate, BigDecimal totalAmount, String status) {}
    public record Result(List<Row> content, long totalElements, int totalPages, int number) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public Result list(UUID branchId, String status, String search, int page, int size) {
        var actor = policy.actor();
        var role = policy.role(actor);
        if (!Set.of("SUPER_ADMIN", "BRANCH_MANAGER").contains(role)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (page < 0 || size < 1 || size > 100
                || (status != null && !Set.of("PENDING", "PAID", "CANCELLED", "REFUNDED").contains(status))) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        Specification<OrderEntity> filter = (root, query, cb) -> cb.conjunction();
        if ("BRANCH_MANAGER".equals(role)) {
            Set<UUID> allowed = new HashSet<>(assignments.branchIdsForUser(actor.getId()));
            if (actor.getHomeBranchId() != null) allowed.add(actor.getHomeBranchId());
            if (branchId != null && !allowed.contains(branchId)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
            if (allowed.isEmpty()) return new Result(List.of(), 0, 0, page);
            filter = filter.and((root, query, cb) -> root.get("branchId").in(allowed));
        }
        if (branchId != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("branchId"), branchId));
        if (status != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        if (search != null && !search.isBlank()) {
            String term = "%" + search.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filter = filter.and((root, query, cb) -> cb.like(cb.lower(root.get("orderCode")), term, '!'));
        }
        var result = orders.findAll(filter, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "orderDate", "id")));
        var customerMap = users.findAllById(result.map(OrderEntity::getCustomerId).toList()).stream()
                .collect(Collectors.toMap(u -> u.getId(), u -> u));
        var branchMap = branches.findAllById(result.map(OrderEntity::getBranchId).toList()).stream()
                .collect(Collectors.toMap(b -> b.getId(), b -> b.getName()));
        var rows = result.getContent().stream().map(o -> {
            var customer = customerMap.get(o.getCustomerId());
            return new Row(o.getId(), o.getOrderCode(), o.getBranchId(), branchMap.get(o.getBranchId()),
                    o.getCustomerId(), customer == null ? null : customer.getFullName(),
                    customer == null ? null : customer.getPhone(),
                    customer == null ? null : customer.getEmail(),
                    o.getOrderDate(), o.getTotalAmount(), o.getStatus());
        }).toList();
        return new Result(rows, result.getTotalElements(), result.getTotalPages(), result.getNumber());
    }
}
