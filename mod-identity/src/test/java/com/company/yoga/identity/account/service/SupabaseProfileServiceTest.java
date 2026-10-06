package com.company.yoga.identity.account.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.company.yoga.identity.account.entity.*;
import com.company.yoga.identity.account.repository.*;
import com.company.yoga.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
class SupabaseProfileServiceTest {
 private UserRepository users; private RoleRepository roles; private SupabaseProfileService service;
 private ObjectNode identity; private RoleEntity student;
 @BeforeEach void setup(){
  users=mock(UserRepository.class);roles=mock(RoleRepository.class);var em=mock(EntityManager.class);var query=mock(Query.class);
  when(em.createNativeQuery(anyString())).thenReturn(query);when(query.setParameter(anyString(),any())).thenReturn(query);
  service=new SupabaseProfileService(users,roles,em);identity=new ObjectMapper().createObjectNode();identity.put("id",UUID.randomUUID().toString());identity.put("email","student@example.com");identity.put("email_confirmed_at","2026-10-03T00:00:00Z");
  identity.putObject("user_metadata").put("phone","0909111222").put("full_name","Test Student").put("role","SUPER_ADMIN");
  student=new RoleEntity();student.setId(UUID.randomUUID());student.setCode("STUDENT");
 }
 @Test void metadataCannotGrantAdminRole(){when(roles.findByCode("STUDENT")).thenReturn(Optional.of(student));when(users.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));var user=service.resolve(identity);assertThat(user.getRoleId()).isEqualTo(student.getId());assertThat(user.getSupabaseUserId().toString()).isEqualTo(identity.get("id").asText());assertThat(user.getPasswordHash()).isEqualTo("!SUPABASE_ONLY");}
 @Test void linkingStudentPreservesBusinessId(){UserEntity user=new UserEntity();user.setId(UUID.randomUUID());user.setRoleId(student.getId());user.setIsActive(true);when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));when(roles.findById(student.getId())).thenReturn(Optional.of(student));when(users.saveAndFlush(user)).thenReturn(user);UUID oldId=user.getId();assertThat(service.resolve(identity).getId()).isEqualTo(oldId);assertThat(user.getSupabaseUserId().toString()).isEqualTo(identity.get("id").asText());}
 @Test void legacyStaffCannotBeClaimedByEmail(){UserEntity user=new UserEntity();user.setRoleId(UUID.randomUUID());user.setIsActive(true);RoleEntity admin=new RoleEntity();admin.setCode("SUPER_ADMIN");when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));when(roles.findById(user.getRoleId())).thenReturn(Optional.of(admin));assertThatThrownBy(()->service.resolve(identity)).isInstanceOf(BusinessException.class);verify(users,never()).saveAndFlush(any());}
 @Test void disabledLinkedAccountIsRejected(){UserEntity user=new UserEntity();user.setIsActive(false);when(users.findBySupabaseUserId(any())).thenReturn(Optional.of(user));assertThatThrownBy(()->service.resolve(identity)).isInstanceOf(BusinessException.class);}
 @Test void unconfirmedEmailCannotProvision(){identity.remove("email_confirmed_at");assertThatThrownBy(()->service.resolve(identity)).isInstanceOf(BusinessException.class);verify(users,never()).saveAndFlush(any());}
 @Test void phoneCollisionDoesNotClaimExistingProfile(){when(users.existsByPhone(anyString())).thenReturn(true);assertThatThrownBy(()->service.resolve(identity)).isInstanceOf(BusinessException.class);verify(users,never()).saveAndFlush(any());}
}
