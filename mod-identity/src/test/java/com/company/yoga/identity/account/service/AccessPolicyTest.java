package com.company.yoga.identity.account.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.company.yoga.identity.account.entity.*;
import com.company.yoga.identity.account.repository.*;
import com.company.yoga.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
class AccessPolicyTest {
 private UserRepository users; private RoleRepository roles; private UserBranchRepository assignments; private AccessPolicy policy; private UserEntity caller; private UUID branch;
 @BeforeEach void setup(){ users=mock(UserRepository.class); roles=mock(RoleRepository.class); assignments=mock(UserBranchRepository.class); policy=new AccessPolicy(users,roles,assignments); branch=UUID.randomUUID(); caller=new UserEntity(); caller.setId(UUID.randomUUID()); caller.setRoleId(UUID.randomUUID()); caller.setHomeBranchId(branch); caller.setIsActive(true); when(users.findById(caller.getId())).thenReturn(Optional.of(caller)); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(caller.getId().toString(),null,List.of())); role("STUDENT"); }
 private void role(String code){RoleEntity role=new RoleEntity();role.setCode(code);when(roles.findById(caller.getRoleId())).thenReturn(Optional.of(role));}
 @AfterEach void clean(){SecurityContextHolder.clearContext();}
 @Test void studentCanUseOwnBooking(){policy.requireBooking(caller.getId(),branch);}
 @Test void studentCannotReadOthers(){assertThatThrownBy(()->policy.requireBooking(UUID.randomUUID(),branch)).isInstanceOf(BusinessException.class);}
 @Test void staffCannotUseOtherBranch(){role("RECEPTIONIST");assertThatThrownBy(()->policy.requireStaffBranch(UUID.randomUUID())).isInstanceOf(BusinessException.class);}
 @Test void assignedBranchIsAllowed(){role("BRANCH_MANAGER");UUID other=UUID.randomUUID();when(assignments.existsByUserIdAndBranchId(caller.getId(),other)).thenReturn(true);assertThat(policy.requireStaffBranch(other)).isEqualTo(caller.getId());}
 @Test void disabledActorIsRejected(){caller.setIsActive(false);assertThatThrownBy(()->policy.actor()).isInstanceOf(BusinessException.class);}
 @Test void instructorCannotCheckInOtherInstructor(){role("INSTRUCTOR");assertThatThrownBy(()->policy.requireInstructorSchedule(branch,UUID.randomUUID())).isInstanceOf(BusinessException.class);}
}
