package com.company.yoga.identity.account.service;

import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.common.util.SecurityUtils;
import com.company.yoga.identity.IdentityResultCodes;
import com.company.yoga.identity.account.dto.UserDto;
import com.company.yoga.identity.account.entity.RoleEntity;
import com.company.yoga.identity.account.entity.UserBranchEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final AccessPolicy accessPolicy;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserBranchRepository userBranchRepository;
    private final BranchRepository branchRepository;
    private final SupabaseAuthGateway supabase;

    @Transactional(readOnly = true)
    public UserDto.UserResponse lookupStudent(String phone, UUID branchId) {
        accessPolicy.requireStaffBranch(branchId);
        UserEntity u = userRepository.findByPhone(phone.trim()).filter(x -> Boolean.TRUE.equals(x.getIsActive()))
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.USER_NOT_FOUND));
        RoleEntity role = roleRepository.findById(u.getRoleId()).orElseThrow();
        if (!"STUDENT".equals(role.getCode())) throw new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION);
        String branchName = u.getHomeBranchId() == null ? null : branchRepository.findById(u.getHomeBranchId()).map(BranchEntity::getName).orElse(null);
        return new UserDto.UserResponse(u.getId(), u.getPhone(), u.getEmail(), u.getFullName(), u.getGender(), u.getDob(), role.getId(), role.getCode(), role.getName(), u.getHomeBranchId(), branchName, u.getIsActive(), u.getCreatedAt());
    }

    private static final Set<String> MANAGER_ALLOWED_ROLES = Set.of("RECEPTIONIST", "INSTRUCTOR", "STUDENT");

    @Transactional
    public UserDto.UserResponse createUser(UserDto.CreateUserRequest request) {
        accessPolicy.actor();
        UUID currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION));

        UserEntity currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.USER_NOT_FOUND));

        RoleEntity callerRole = roleRepository.findById(currentUser.getRoleId())
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.ROLE_NOT_FOUND));

        String callerRoleCode = callerRole.getCode();
        String targetRoleCode = request.roleCode().trim().toUpperCase();

        // 1. Kiểm tra quyền hạn theo quy định:
        // - SUPER_ADMIN: Có thể tạo tài khoản cho tất cả các role khác ở bất kỳ chi nhánh nào
        // - BRANCH_MANAGER: Chỉ có thể tạo tk cho RECEPTIONIST, INSTRUCTOR, STUDENT thuộc chi nhánh của mình
        if ("SUPER_ADMIN".equalsIgnoreCase(callerRoleCode)) {
            // Hợp lệ, Super Admin có toàn quyền
        } else if ("BRANCH_MANAGER".equalsIgnoreCase(callerRoleCode)) {
            if (!MANAGER_ALLOWED_ROLES.contains(targetRoleCode)) {
                log.warn("Branch Manager {} attempted to create forbidden role: {}", currentUserId, targetRoleCode);
                throw new BusinessException(IdentityResultCodes.INVALID_ROLE_ASSIGNMENT);
            }

            // Kiểm tra chi nhánh: phải là chi nhánh mà Manager phụ trách
            UUID managerBranchId = currentUser.getHomeBranchId();
            if (managerBranchId == null || !managerBranchId.equals(request.homeBranchId())) {
                // Kiểm tra xem Manager có được phân công phụ trách branch này trong user_branches không
                boolean isAssigned = request.homeBranchId() != null &&
                        userBranchRepository.existsByUserIdAndBranchId(currentUserId, request.homeBranchId());
                if (!isAssigned) {
                    log.warn("Branch Manager {} attempted to create user for unmanaged branch: {}", currentUserId, request.homeBranchId());
                    throw new BusinessException(IdentityResultCodes.INVALID_BRANCH_ASSIGNMENT);
                }
            }
        } else {
            log.warn("User {} with role {} attempted to create user", currentUserId, callerRoleCode);
            throw new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION);
        }

        // 2. Validate trùng số điện thoại
        String phone = request.phone().trim();
        if (userRepository.existsByPhone(phone)) {
            throw new BusinessException(IdentityResultCodes.PHONE_ALREADY_EXISTS);
        }

        // 3. Validate trùng email
        String email = request.email() != null && !request.email().isBlank() ? request.email().trim().toLowerCase() : null;
        if (email != null && userRepository.existsByEmail(email)) {
            throw new BusinessException(IdentityResultCodes.EMAIL_ALREADY_EXISTS);
        }

        if (email == null) throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST, "Email bắt buộc cho tài khoản Supabase.");

        // 4. Tìm target role trong database
        RoleEntity targetRole = roleRepository.findByCode(targetRoleCode)
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.ROLE_NOT_FOUND));

        // 5. Kiểm tra chi nhánh nếu có
        UUID branchId = request.homeBranchId();
        String branchName = null;
        if (branchId != null) {
            BranchEntity branch = branchRepository.findById(branchId).filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                    .orElseThrow(() -> new BusinessException(IdentityResultCodes.INVALID_BRANCH_ASSIGNMENT));
            if (branch != null) {
                branchName = branch.getName();
            }
        }

        // 6. Lưu user mới
        UserEntity newUser = new UserEntity();
        UUID authId = supabase.createUser(email, request.password(), request.fullName().trim(), phone);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) { try { supabase.removeCreatedUser(authId); } catch (Exception ex) { log.error("Supabase user {} needs reconciliation after profile rollback", authId); } }
            }
        });
        newUser.setId(authId);
        newUser.setSupabaseUserId(authId);
        newUser.setPhone(phone);
        newUser.setEmail(email);
        newUser.setFullName(request.fullName().trim());
        newUser.setPasswordHash("!SUPABASE_ONLY");
        newUser.setGender(request.gender());
        newUser.setDob(request.dob());
        newUser.setRoleId(targetRole.getId());
        newUser.setHomeBranchId(branchId);
        newUser.setIsActive(true);

        UserEntity saved = userRepository.save(newUser);

        // Nếu là nhân sự có chi nhánh, ghi vào user_branches
        if (branchId != null && !"STUDENT".equalsIgnoreCase(targetRoleCode)) {
            UserBranchEntity userBranch = new UserBranchEntity();
            userBranch.setId(UUID.randomUUID());
            userBranch.setUserId(saved.getId());
            userBranch.setBranchId(branchId);
            userBranch.setIsPrimary(true);
            userBranchRepository.save(userBranch);
        }

        return new UserDto.UserResponse(
                saved.getId(),
                saved.getPhone(),
                saved.getEmail(),
                saved.getFullName(),
                saved.getGender(),
                saved.getDob(),
                targetRole.getId(),
                targetRole.getCode(),
                targetRole.getName(),
                saved.getHomeBranchId(),
                branchName,
                saved.getIsActive(),
                saved.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<UserDto.UserResponse> getUsers(UUID branchFilter, String roleFilter) {
        UUID currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION));

        UserEntity currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.USER_NOT_FOUND));

        RoleEntity callerRole = roleRepository.findById(currentUser.getRoleId())
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.ROLE_NOT_FOUND));

        String callerRoleCode = callerRole.getCode();

        List<UserEntity> rawList;
        if ("SUPER_ADMIN".equalsIgnoreCase(callerRoleCode)) {
            if (branchFilter != null) {
                rawList = userRepository.findByHomeBranchIdOrderByCreatedAtDesc(branchFilter);
            } else {
                rawList = userRepository.findAllByOrderByCreatedAtDesc();
            }
        } else if ("BRANCH_MANAGER".equalsIgnoreCase(callerRoleCode)) {
            UUID managerBranchId = currentUser.getHomeBranchId();
            if (managerBranchId == null) {
                return List.of();
            }
            rawList = userRepository.findByHomeBranchIdOrderByCreatedAtDesc(managerBranchId);
        } else {
            throw new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION);
        }

        // Cache roles and branches for fast lookup
        Map<UUID, RoleEntity> roleMap = roleRepository.findAll().stream()
                .collect(Collectors.toMap(RoleEntity::getId, r -> r));

        Map<UUID, String> branchNameMap = branchRepository.findAll().stream()
                .collect(Collectors.toMap(BranchEntity::getId, BranchEntity::getName));

        return rawList.stream()
                .filter(u -> {
                    if (roleFilter == null || roleFilter.isBlank()) return true;
                    RoleEntity r = roleMap.get(u.getRoleId());
                    return r != null && r.getCode().equalsIgnoreCase(roleFilter.trim());
                })
                .map(u -> {
                    RoleEntity r = roleMap.get(u.getRoleId());
                    String rCode = r != null ? r.getCode() : "UNKNOWN";
                    String rName = r != null ? r.getName() : "Chưa xác định";
                    String bName = u.getHomeBranchId() != null ? branchNameMap.get(u.getHomeBranchId()) : null;

                    return new UserDto.UserResponse(
                            u.getId(),
                            u.getPhone(),
                            u.getEmail(),
                            u.getFullName(),
                            u.getGender(),
                            u.getDob(),
                            u.getRoleId(),
                            rCode,
                            rName,
                            u.getHomeBranchId(),
                            bName,
                            u.getIsActive(),
                            u.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public UserDto.UserResponse updateUserStatus(UUID targetUserId, boolean isActive) {
        accessPolicy.actor();
        UUID currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION));

        if (currentUserId.equals(targetUserId)) {
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST, "Bạn không thể tự thay đổi trạng thái tài khoản của chính mình.");
        }

        UserEntity currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.USER_NOT_FOUND));

        RoleEntity callerRole = roleRepository.findById(currentUser.getRoleId())
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.ROLE_NOT_FOUND));

        UserEntity targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.USER_NOT_FOUND));

        RoleEntity targetRole = roleRepository.findById(targetUser.getRoleId())
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.ROLE_NOT_FOUND));

        String callerRoleCode = callerRole.getCode();
        String targetRoleCode = targetRole.getCode();

        if ("SUPER_ADMIN".equalsIgnoreCase(callerRoleCode)) {
            // Super Admin có toàn quyền đổi trạng thái của các tài khoản khác
        } else if ("BRANCH_MANAGER".equalsIgnoreCase(callerRoleCode)) {
            if (!MANAGER_ALLOWED_ROLES.contains(targetRoleCode)) {
                log.warn("Branch Manager {} attempted to change status of role {}", currentUserId, targetRoleCode);
                throw new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION);
            }

            UUID managerBranchId = currentUser.getHomeBranchId();
            boolean isSameBranch = managerBranchId != null && managerBranchId.equals(targetUser.getHomeBranchId());
            boolean isAssigned = targetUser.getHomeBranchId() != null &&
                    userBranchRepository.existsByUserIdAndBranchId(currentUserId, targetUser.getHomeBranchId());

            if (!isSameBranch && !isAssigned) {
                log.warn("Branch Manager {} attempted to change status of user outside branch", currentUserId);
                throw new BusinessException(IdentityResultCodes.INVALID_BRANCH_ASSIGNMENT);
            }
        } else {
            throw new BusinessException(IdentityResultCodes.FORBIDDEN_ACTION);
        }

        targetUser.setIsActive(isActive);
        UserEntity saved = userRepository.save(targetUser);

        String branchName = saved.getHomeBranchId() != null ?
                branchRepository.findById(saved.getHomeBranchId()).map(BranchEntity::getName).orElse(null) : null;

        return new UserDto.UserResponse(
                saved.getId(),
                saved.getPhone(),
                saved.getEmail(),
                saved.getFullName(),
                saved.getGender(),
                saved.getDob(),
                targetRole.getId(),
                targetRole.getCode(),
                targetRole.getName(),
                saved.getHomeBranchId(),
                branchName,
                saved.getIsActive(),
                saved.getCreatedAt()
        );
    }
}
