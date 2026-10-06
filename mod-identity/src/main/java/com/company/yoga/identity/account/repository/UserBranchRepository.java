package com.company.yoga.identity.account.repository;

import com.company.yoga.identity.account.entity.UserBranchEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserBranchRepository extends JpaRepository<UserBranchEntity, UUID> {

    @org.springframework.data.jpa.repository.Query("SELECT a.branchId FROM UserBranchEntity a WHERE a.userId = :id")
    List<UUID> branchIdsForUser(@org.springframework.data.repository.query.Param("id") UUID id);
    List<UserBranchEntity> findByUserId(UUID userId);

    List<UserBranchEntity> findByBranchId(UUID branchId);

    boolean existsByUserIdAndBranchId(UUID userId, UUID branchId);

    Optional<UserBranchEntity> findByUserIdAndBranchId(UUID userId, UUID branchId);
}
