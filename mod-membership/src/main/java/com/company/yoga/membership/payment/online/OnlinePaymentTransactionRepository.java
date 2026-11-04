package com.company.yoga.membership.payment.online;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OnlinePaymentTransactionRepository extends JpaRepository<OnlinePaymentTransactionEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM OnlinePaymentTransactionEntity t WHERE t.id = :id")
    Optional<OnlinePaymentTransactionEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<OnlinePaymentTransactionEntity> findByPaymentId(UUID paymentId);

    Optional<OnlinePaymentTransactionEntity> findByProviderAndProviderOrderCode(String provider, Long providerOrderCode);

    @Query("""
            SELECT t FROM OnlinePaymentTransactionEntity t, com.company.yoga.membership.order.entity.PaymentEntity p
            WHERE p.id = t.paymentId AND p.orderId = :orderId AND t.status = 'CREATED'
            """)
    Optional<OnlinePaymentTransactionEntity> findOpenByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT t FROM OnlinePaymentTransactionEntity t WHERE t.status = 'CREATED' AND t.createdAt < :before ORDER BY t.expiresAt")
    List<OnlinePaymentTransactionEntity> findOpenCreatedBefore(@Param("before") Instant before, Pageable page);
}
