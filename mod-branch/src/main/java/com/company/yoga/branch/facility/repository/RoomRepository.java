package com.company.yoga.branch.facility.repository;

import com.company.yoga.branch.facility.entity.RoomEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomRepository extends JpaRepository<RoomEntity, UUID> {
    List<RoomEntity> findByBranchId(UUID branchId);
    boolean existsByBranchIdAndName(UUID branchId, String name);
    boolean existsByBranchIdAndNameAndIdNot(UUID branchId, String name, UUID id);
    long countByBranchId(UUID branchId);
}
