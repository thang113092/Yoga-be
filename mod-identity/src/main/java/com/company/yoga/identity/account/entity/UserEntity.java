package com.company.yoga.identity.account.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity extends BaseAuditEntity {

    @Column(name = "supabase_user_id", unique = true)
    private UUID supabaseUserId;

    @Column(name = "phone", nullable = false, unique = true, length = 20)
    private String phone;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "home_branch_id")
    private UUID homeBranchId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
