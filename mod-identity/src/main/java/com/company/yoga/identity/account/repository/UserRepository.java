package com.company.yoga.identity.account.repository;

import com.company.yoga.identity.account.entity.UserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findBySupabaseUserId(UUID supabaseUserId);
    Optional<UserEntity> findByEmailIgnoreCase(String email);
    Optional<UserEntity> findByPhone(String phone);
    boolean existsByPhone(String phone);
    boolean existsByEmail(String email);
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.homeBranchId = :branchId AND u.passwordHash <> '!DELETED' ORDER BY u.createdAt DESC")
    java.util.List<UserEntity> findByHomeBranchIdOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("branchId") UUID branchId);
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.passwordHash <> '!DELETED' ORDER BY u.createdAt DESC")
    java.util.List<UserEntity> findAllByOrderByCreatedAtDesc();
}
