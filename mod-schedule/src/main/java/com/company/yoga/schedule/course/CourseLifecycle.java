package com.company.yoga.schedule.course;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Release abandoned reservations even when no new learner opens the class. */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class CourseLifecycle {
    private final CourseDao dao;
    private final TransactionTemplate transactions;

    @Scheduled(fixedDelayString="${yoga.courses.lifecycle-delay-ms:60000}")
    public void reconcile() {
        var expired=dao.rows("""
            SELECT i.order_id FROM yoga.course_enrollments e JOIN yoga.order_items i ON i.id=e.source_order_item_id
            WHERE e.status='PENDING_PAYMENT' AND e.reserved_until<=CURRENT_TIMESTAMP
            AND NOT EXISTS(SELECT 1 FROM yoga.payments p WHERE p.order_id=i.order_id AND p.payment_status<>'FAILED')
            LIMIT 100
            """,Map.of());
        for(var row:expired) {
            try { transactions.executeWithoutResult(tx->dao.rows("SELECT yoga.cancel_pending_course_order(:id)",Map.of("id",(UUID)row.get("orderId")))); }
            catch(RuntimeException ex) { log.warn("Course reservation requires reconciliation for order {}",row.get("orderId")); }
        }
        transactions.executeWithoutResult(tx->{
            dao.rows("SELECT id FROM yoga.course_classes WHERE status IN ('OPEN','IN_PROGRESS') ORDER BY id FOR UPDATE",Map.of());
            dao.update("""
                UPDATE yoga.class_schedules s SET status='IN_PROGRESS' FROM yoga.course_classes c WHERE s.course_class_id=c.id
                AND c.status IN ('OPEN','IN_PROGRESS') AND s.status='SCHEDULED' AND s.start_time<=CURRENT_TIMESTAMP AND s.end_time+INTERVAL '12 hours'>CURRENT_TIMESTAMP
                """,Map.of());
            dao.update("""
                UPDATE yoga.class_schedules s SET status='COMPLETED' FROM yoga.course_classes c WHERE s.course_class_id=c.id
                AND c.status IN ('OPEN','IN_PROGRESS') AND s.status IN ('SCHEDULED','IN_PROGRESS') AND s.end_time+INTERVAL '12 hours'<=CURRENT_TIMESTAMP
                """,Map.of());
            dao.update("""
                UPDATE yoga.course_classes c SET status='IN_PROGRESS' WHERE c.status='OPEN' AND EXISTS(
                SELECT 1 FROM yoga.class_schedules s WHERE s.course_class_id=c.id AND s.status<>'CANCELLED' AND s.start_time<=CURRENT_TIMESTAMP)
                """,Map.of());
            dao.update("""
                UPDATE yoga.course_classes c SET status='COMPLETED' WHERE c.status='IN_PROGRESS' AND
                (SELECT count(*) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED')=c.planned_sessions AND
                NOT EXISTS(SELECT 1 FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED' AND end_time+INTERVAL '12 hours'>CURRENT_TIMESTAMP)
                """,Map.of());
            dao.update("""
                UPDATE yoga.course_enrollments e SET status='COMPLETED' FROM yoga.course_classes c
                WHERE c.id=e.course_class_id AND c.status='COMPLETED' AND e.status='ACTIVE'
                """,Map.of());
        });
    }
}
