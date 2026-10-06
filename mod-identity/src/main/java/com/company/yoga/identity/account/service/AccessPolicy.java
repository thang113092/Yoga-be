package com.company.yoga.identity.account.service;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.common.util.SecurityUtils;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessPolicy {
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserBranchRepository assignments;

    public UserEntity actor() {
        UUID id = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        return users.findById(id).filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }

    public String role(UserEntity user) {
        return roles.findById(user.getRoleId()).map(r -> r.getCode().toUpperCase())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    public UUID requireStaffBranch(UUID branchId) {
        UserEntity caller = actor();
        String role = role(caller);
        if (!Set.of("SUPER_ADMIN", "BRANCH_MANAGER", "RECEPTIONIST", "INSTRUCTOR").contains(role)
                || (branchId == null && !"SUPER_ADMIN".equals(role))) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (!"SUPER_ADMIN".equals(role) && !Objects.equals(branchId, caller.getHomeBranchId())
                && !assignments.existsByUserIdAndBranchId(caller.getId(), branchId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return caller.getId();
    }

    public void requireStudent(UUID studentId) {
        UserEntity caller = actor();
        if (caller.getId().equals(studentId) && "STUDENT".equals(role(caller))) return;
        UserEntity student = users.findById(studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!"STUDENT".equals(role(student))) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        requireStaffBranch(student.getHomeBranchId());
    }

    public void requireBooking(UUID studentId, UUID branchId) {
        UserEntity caller = actor();
        if (caller.getId().equals(studentId) && "STUDENT".equals(role(caller))) return;
        requireStaffBranch(branchId);
        UserEntity student = users.findById(studentId).filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!"STUDENT".equals(role(student))) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
    }

    public void requireInstructorSchedule(UUID branchId, UUID instructorId) {
        UUID caller = requireStaffBranch(branchId);
        if ("INSTRUCTOR".equals(role(actor())) && !caller.equals(instructorId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
