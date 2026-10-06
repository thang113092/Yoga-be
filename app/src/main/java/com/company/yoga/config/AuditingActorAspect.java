package com.company.yoga.config;

import com.company.yoga.common.util.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(10)
@Slf4j
public class AuditingActorAspect {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Tự động gán biến ngữ cảnh PostgreSQL SET LOCAL app.actor_id cho mỗi Transaction ghi.
     * SET LOCAL chỉ tồn tại trong phạm vi transaction hiện tại, tự giải phóng khi commit/rollback,
     * ngăn chặn hoàn toàn rò rỉ context khi HikariCP tái sử dụng connection.
     */
    @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void setAuditActor() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        SecurityUtils.getCurrentUserId().ifPresent(actorId -> {
            {
                entityManager.createNativeQuery("SET LOCAL app.actor_id = '" + actorId + "'")
                        .executeUpdate();
            }
        });
    }
}
