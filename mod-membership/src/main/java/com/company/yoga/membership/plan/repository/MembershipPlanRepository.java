package com.company.yoga.membership.plan.repository;

import com.company.yoga.membership.plan.entity.MembershipPlanEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MembershipPlanRepository extends JpaRepository<MembershipPlanEntity, UUID> {
    Optional<MembershipPlanEntity> findByCode(String code);
    List<MembershipPlanEntity> findByIsActiveTrue();
}
