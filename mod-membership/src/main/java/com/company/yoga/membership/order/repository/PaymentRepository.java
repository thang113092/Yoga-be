package com.company.yoga.membership.order.repository;

import com.company.yoga.membership.order.entity.PaymentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByIdempotencyKey(UUID idempotencyKey);

    List<PaymentEntity> findByOrderId(UUID orderId);
}
