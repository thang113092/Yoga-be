package com.company.yoga.membership.order.repository;

import com.company.yoga.membership.order.entity.OrderEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<OrderEntity> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    Optional<OrderEntity> findByIdWithLock(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<OrderEntity> findByOrderCode(String orderCode);

    List<OrderEntity> findByCustomerIdOrderByOrderDateDesc(UUID customerId);

    List<OrderEntity> findByBranchIdOrderByOrderDateDesc(UUID branchId);
}
