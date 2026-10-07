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

    @Test
    void lookupStudent_byPhone_success() {
        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");

        UserEntity student = new UserEntity();
        student.setId(UUID.randomUUID());
        student.setPhone("0987654321");
        student.setEmail("student@gmail.com");
        student.setFullName("Nguyen Van A");
        student.setRoleId(studentRoleId);
        student.setIsActive(true);

        when(userRepository.findByPhone("0987654321")).thenReturn(Optional.of(student));
        when(roleRepository.findById(studentRoleId)).thenReturn(Optional.of(studentRole));

        UserDto.UserResponse resp = userService.lookupStudent("0987654321", null, managerBranchId);

        assertThat(resp.fullName()).isEqualTo("Nguyen Van A");
        assertThat(resp.phone()).isEqualTo("0987654321");
        assertThat(resp.email()).isEqualTo("student@gmail.com");
    }

    @Test
    void lookupStudent_byEmail_success() {
        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");

        UserEntity student = new UserEntity();
        student.setId(UUID.randomUUID());
        student.setPhone("0987654321");
        student.setEmail("student@gmail.com");
        student.setFullName("Nguyen Van B");
        student.setRoleId(studentRoleId);
        student.setIsActive(true);

        when(userRepository.findByEmailIgnoreCase("student@gmail.com")).thenReturn(Optional.of(student));
        when(roleRepository.findById(studentRoleId)).thenReturn(Optional.of(studentRole));

        UserDto.UserResponse resp = userService.lookupStudent(null, "student@gmail.com", managerBranchId);

        assertThat(resp.fullName()).isEqualTo("Nguyen Van B");
        assertThat(resp.phone()).isEqualTo("0987654321");
        assertThat(resp.email()).isEqualTo("student@gmail.com");
    }

    @Test
    void receptionist_getUsers_returnsOnlyStudents() {
        UUID receptionistRoleId = UUID.randomUUID();
        RoleEntity receptionistRole = new RoleEntity();
        receptionistRole.setId(receptionistRoleId);
        receptionistRole.setCode("RECEPTIONIST");
        receptionistRole.setName("Lễ tân");

        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");
        studentRole.setName("Học viên");

        callerManager.setRoleId(receptionistRoleId);
        when(roleRepository.findById(receptionistRoleId)).thenReturn(Optional.of(receptionistRole));
        when(roleRepository.findAll()).thenReturn(List.of(receptionistRole, studentRole));

        UserEntity studentUser = new UserEntity();
        studentUser.setId(UUID.randomUUID());
        studentUser.setRoleId(studentRoleId);
        studentUser.setFullName("Hoc Vien 1");

        UserEntity staffUser = new UserEntity();
        staffUser.setId(UUID.randomUUID());
        staffUser.setRoleId(receptionistRoleId);
        staffUser.setFullName("Le Tan 2");

        when(userRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(studentUser, staffUser));

        List<UserDto.UserResponse> result = userService.getUsers((String) null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).roleCode()).isEqualTo("STUDENT");
        assertThat(result.get(0).fullName()).isEqualTo("Hoc Vien 1");
    }

    @Test
    void receptionist_createUser_studentWithoutBranch_success() {
        UUID receptionistRoleId = UUID.randomUUID();
        RoleEntity receptionistRole = new RoleEntity();
        receptionistRole.setId(receptionistRoleId);
        receptionistRole.setCode("RECEPTIONIST");

        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");
        studentRole.setName("Học viên");

        callerManager.setRoleId(receptionistRoleId);
        when(roleRepository.findById(receptionistRoleId)).thenReturn(Optional.of(receptionistRole));
        when(roleRepository.findByCode("STUDENT")).thenReturn(Optional.of(studentRole));

        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(userRepository.existsByEmail("student@an-yen.vn")).thenReturn(false);
        UUID authId = UUID.randomUUID();
        when(supabase.createUser(any(), any(), any(), any())).thenReturn(authId);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto.CreateUserRequest req = new UserDto.CreateUserRequest(
                "0912345678",
                "Password123",
                "Le Thi Hoc Vien",
                "student@an-yen.vn",
                "FEMALE",
                null,
                "STUDENT",
                null // Khong can chon co so
        );

        UserDto.UserResponse resp = userService.createUser(req);

        assertThat(resp.fullName()).isEqualTo("Le Thi Hoc Vien");
        assertThat(resp.homeBranchId()).isNull();
        assertThat(resp.roleCode()).isEqualTo("STUDENT");
    }

    @Test
    void receptionist_createUser_forbiddenForOtherRoles() {
        UUID receptionistRoleId = UUID.randomUUID();
        RoleEntity receptionistRole = new RoleEntity();
        receptionistRole.setId(receptionistRoleId);
        receptionistRole.setCode("RECEPTIONIST");

        callerManager.setRoleId(receptionistRoleId);
        when(roleRepository.findById(receptionistRoleId)).thenReturn(Optional.of(receptionistRole));

        UserDto.CreateUserRequest req = new UserDto.CreateUserRequest(
                "0912345678",
                "Password123",
                "Nhan Vien Khac",
                "staff@an-yen.vn",
                "FEMALE",
                null,
                "INSTRUCTOR",
                null
        );

        assertThatThrownBy(() -> userService.createUser(req))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void searchStudents_success() {
        UUID studentRoleId = UUID.randomUUID();
        RoleEntity studentRole = new RoleEntity();
        studentRole.setId(studentRoleId);
        studentRole.setCode("STUDENT");

        UserEntity student = new UserEntity();
        student.setId(UUID.randomUUID());
        student.setPhone("0987654321");
        student.setEmail("search@student.com");
        student.setFullName("Nguyen Search");
        student.setRoleId(studentRoleId);
        student.setIsActive(true);

        when(roleRepository.findByCode("STUDENT")).thenReturn(Optional.of(studentRole));
        when(userRepository.searchStudents(eq("Search"), eq(studentRoleId), any()))
                .thenReturn(List.of(student));

        List<UserDto.UserResponse> results = userService.searchStudents("Search", managerBranchId, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fullName()).isEqualTo("Nguyen Search");
    }

    @Test
    void instructor_canGetInstructorsList() {
        UUID instructorRoleId = UUID.randomUUID();
        RoleEntity instructorRole = new RoleEntity();
        instructorRole.setId(instructorRoleId);
        instructorRole.setCode("INSTRUCTOR");

        callerManager.setRoleId(instructorRoleId);
        when(roleRepository.findById(instructorRoleId)).thenReturn(Optional.of(instructorRole));
        when(roleRepository.findAll()).thenReturn(List.of(instructorRole));
        when(branchRepository.findAll()).thenReturn(List.of());

        UserEntity otherInstructor = new UserEntity();
        otherInstructor.setId(UUID.randomUUID());
        otherInstructor.setPhone("0911223344");
        otherInstructor.setEmail("instructor2@an-yen.vn");
        otherInstructor.setFullName("Nguyen Van HLV");
        otherInstructor.setRoleId(instructorRoleId);
        otherInstructor.setIsActive(true);

        when(userRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(callerManager, otherInstructor));

        List<UserDto.UserResponse> results = userService.getUsers((String) null, "INSTRUCTOR");

        assertThat(results).hasSize(2);
        assertThat(results.get(1).fullName()).isEqualTo("Nguyen Van HLV");
    }
}
