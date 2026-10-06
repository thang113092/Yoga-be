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

    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE (u.homeBranchId = :branchId OR u.homeBranchId IS NULL) AND u.passwordHash <> '!DELETED' ORDER BY u.createdAt DESC")
    java.util.List<UserEntity> findByHomeBranchIdOrHomeBranchIdIsNullOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("branchId") UUID branchId);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.homeBranchId IS NULL AND u.passwordHash <> '!DELETED' ORDER BY u.createdAt DESC")
    java.util.List<UserEntity> findByHomeBranchIdIsNullOrderByCreatedAtDesc();

    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.passwordHash <> '!DELETED' ORDER BY u.createdAt DESC")
    java.util.List<UserEntity> findAllByOrderByCreatedAtDesc();

    @org.springframework.data.jpa.repository.Query("""
        SELECT u FROM UserEntity u
        WHERE u.isActive = true
          AND u.passwordHash <> '!DELETED'
          AND u.roleId = :studentRoleId
          AND (
            LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
            OR u.phone LIKE CONCAT('%', :query, '%')
            OR (u.email IS NOT NULL AND LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')))
          )
        ORDER BY u.fullName ASC
    """)
    java.util.List<UserEntity> searchStudents(
            @org.springframework.data.repository.query.Param("query") String query,
            @org.springframework.data.repository.query.Param("studentRoleId") UUID studentRoleId,
            org.springframework.data.domain.Pageable pageable
    );
}
