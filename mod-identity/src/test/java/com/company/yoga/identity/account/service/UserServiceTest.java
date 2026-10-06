package com.company.yoga.identity.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.dto.UserDto;
import com.company.yoga.identity.account.entity.RoleEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserServiceTest {

    private AccessPolicy accessPolicy;
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private UserBranchRepository userBranchRepository;
    private BranchRepository branchRepository;
    private SupabaseAuthGateway supabase;
    private UserService userService;

    private UserEntity callerManager;
    private RoleEntity managerRole;
    private UUID managerBranchId;

    @BeforeEach
    void setup() {
        accessPolicy = mock(AccessPolicy.class);
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        userBranchRepository = mock(UserBranchRepository.class);
        branchRepository = mock(BranchRepository.class);
        supabase = mock(SupabaseAuthGateway.class);

        userService = new UserService(accessPolicy, userRepository, roleRepository, userBranchRepository, branchRepository, supabase);

        managerBranchId = UUID.randomUUID();
        callerManager = new UserEntity();
        callerManager.setId(UUID.randomUUID());
        callerManager.setHomeBranchId(managerBranchId);
        callerManager.setIsActive(true);

        UUID roleId = UUID.randomUUID();
        callerManager.setRoleId(roleId);

        managerRole = new RoleEntity();
        managerRole.setId(roleId);
        managerRole.setCode("BRANCH_MANAGER");
        managerRole.setName("Quản lý cơ sở");

        when(userRepository.findById(callerManager.getId())).thenReturn(Optional.of(callerManager));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(managerRole));
        when(roleRepository.findAll()).thenReturn(List.of(managerRole));
        when(branchRepository.findAll()).thenReturn(List.of());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(callerManager.getId().toString(), null, List.of())
        );
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void branchManager_getUsersDefault_returnsBranchAndUnassignedUsers() {
        UserEntity branchUser = new UserEntity();
        branchUser.setId(UUID.randomUUID());
        branchUser.setRoleId(managerRole.getId());
        branchUser.setHomeBranchId(managerBranchId);

        UserEntity unassignedUser = new UserEntity();
        unassignedUser.setId(UUID.randomUUID());
        unassignedUser.setRoleId(managerRole.getId());
        unassignedUser.setHomeBranchId(null);

        when(userRepository.findByHomeBranchIdOrHomeBranchIdIsNullOrderByCreatedAtDesc(managerBranchId))
                .thenReturn(List.of(branchUser, unassignedUser));

        List<UserDto.UserResponse> result = userService.getUsers((String) null, null);

        assertThat(result).hasSize(2);
        verify(userRepository).findByHomeBranchIdOrHomeBranchIdIsNullOrderByCreatedAtDesc(managerBranchId);
    }

    @Test
    void branchManager_getUsersUnassigned_returnsOnlyUnassignedUsers() {
        UserEntity unassignedUser = new UserEntity();
        unassignedUser.setId(UUID.randomUUID());
        unassignedUser.setRoleId(managerRole.getId());
        unassignedUser.setHomeBranchId(null);

        when(userRepository.findByHomeBranchIdIsNullOrderByCreatedAtDesc())
                .thenReturn(List.of(unassignedUser));

        List<UserDto.UserResponse> result = userService.getUsers("UNASSIGNED", null);

        assertThat(result).hasSize(1);
        verify(userRepository).findByHomeBranchIdIsNullOrderByCreatedAtDesc();
    }

    @Test
    void branchManager_getUsersSpecificOwnBranch_returnsBranchUsers() {
        UserEntity branchUser = new UserEntity();
        branchUser.setId(UUID.randomUUID());
        branchUser.setRoleId(managerRole.getId());
        branchUser.setHomeBranchId(managerBranchId);

        when(userRepository.findByHomeBranchIdOrderByCreatedAtDesc(managerBranchId))
                .thenReturn(List.of(branchUser));

        List<UserDto.UserResponse> result = userService.getUsers(managerBranchId.toString(), null);

        assertThat(result).hasSize(1);
        verify(userRepository).findByHomeBranchIdOrderByCreatedAtDesc(managerBranchId);
    }

    @Test
    void branchManager_updateUserStatus_unassignedStudentAllowed() {
        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");
        studentRole.setName("Học viên");

        UserEntity unassignedStudent = new UserEntity();
        unassignedStudent.setId(UUID.randomUUID());
        unassignedStudent.setRoleId(studentRoleId);
        unassignedStudent.setHomeBranchId(null);
        unassignedStudent.setIsActive(true);

        when(userRepository.findById(unassignedStudent.getId())).thenReturn(Optional.of(unassignedStudent));
        when(roleRepository.findById(studentRoleId)).thenReturn(Optional.of(studentRole));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto.UserResponse resp = userService.updateUserStatus(unassignedStudent.getId(), false);

        assertThat(resp.isActive()).isFalse();
        verify(userRepository).save(unassignedStudent);
    }

    @Test
    void branchManager_updateUserStatus_userOtherBranchForbidden() {
        UUID otherBranchId = UUID.randomUUID();
        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");

        UserEntity otherBranchStudent = new UserEntity();
        otherBranchStudent.setId(UUID.randomUUID());
        otherBranchStudent.setRoleId(studentRoleId);
        otherBranchStudent.setHomeBranchId(otherBranchId);
        otherBranchStudent.setIsActive(true);

        when(userRepository.findById(otherBranchStudent.getId())).thenReturn(Optional.of(otherBranchStudent));
        when(roleRepository.findById(studentRoleId)).thenReturn(Optional.of(studentRole));
        when(userBranchRepository.existsByUserIdAndBranchId(callerManager.getId(), otherBranchId)).thenReturn(false);

        assertThatThrownBy(() -> userService.updateUserStatus(otherBranchStudent.getId(), false))
                .isInstanceOf(BusinessException.class);
    }
}
