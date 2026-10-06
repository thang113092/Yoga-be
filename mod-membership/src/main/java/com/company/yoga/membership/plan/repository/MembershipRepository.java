package com.company.yoga.membership.plan.repository;

import com.company.yoga.membership.plan.entity.MembershipEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MembershipRepository extends JpaRepository<MembershipEntity, UUID> {
    List<MembershipEntity> findByStudentId(UUID studentId);
    Optional<MembershipEntity> findByMembershipCode(String membershipCode);
    Optional<MembershipEntity> findBySourceOrderItemId(UUID sourceOrderItemId);
}
