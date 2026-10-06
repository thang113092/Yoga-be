package com.company.yoga.schedule.booking.repository;

import com.company.yoga.schedule.booking.entity.ClassTypeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassTypeRepository extends JpaRepository<ClassTypeEntity, UUID> {
    Optional<ClassTypeEntity> findByCode(String code);
}
