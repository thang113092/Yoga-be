package com.company.yoga.branch.facility.repository;

import com.company.yoga.branch.facility.entity.BranchEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BranchRepository extends JpaRepository<BranchEntity, UUID> {
    boolean existsByCode(String code);
    boolean existsByCodeAndIdNot(String code, UUID id);
    Optional<BranchEntity> findByCode(String code);
}
