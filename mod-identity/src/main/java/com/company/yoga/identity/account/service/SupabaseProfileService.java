package com.company.yoga.identity.account.service;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class SupabaseProfileService {
 private final UserRepository users; private final RoleRepository roles; private final jakarta.persistence.EntityManager entityManager;
 @Transactional
 public UserEntity resolve(JsonNode identity) {
  UUID authId=UUID.fromString(identity.path("id").asText());
  // Avoid duplicate provisioning for simultaneous initial API requests.
  entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 2))").setParameter("key",authId.toString()).getResultList();
  var linked=users.findBySupabaseUserId(authId);
  if(linked.isPresent()) { if(!Boolean.TRUE.equals(linked.get().getIsActive())) throw new BusinessException(CommonErrorCode.FORBIDDEN,"Tài khoản đã bị khóa."); return linked.get(); }
  String email=identity.path("email").asText("").trim().toLowerCase(java.util.Locale.ROOT);
  if(email.isBlank() || identity.path("email_confirmed_at").isNull() || identity.path("email_confirmed_at").isMissingNode()) throw new BusinessException(CommonErrorCode.FORBIDDEN,"Vui lòng xác nhận email Supabase trước khi tiếp tục.");
  var existing=users.findByEmailIgnoreCase(email);
  if(existing.isPresent()) {
   UserEntity user=existing.get();
   // Legacy staff must be explicitly linked by an administrator, never by metadata/email alone.
   String role=roles.findById(user.getRoleId()).orElseThrow().getCode();
   if(user.getSupabaseUserId()!=null || !"STUDENT".equals(role) || !Boolean.TRUE.equals(user.getIsActive())) throw new BusinessException(CommonErrorCode.FORBIDDEN,"Tài khoản hiện hữu cần quản trị viên liên kết Supabase Auth.");
   user.setSupabaseUserId(authId);return users.saveAndFlush(user);
  }
  JsonNode meta=identity.path("user_metadata");String phone=meta.path("phone").asText("").trim();String name=meta.path("full_name").asText("").trim();
  if(!phone.matches("\\+?[0-9]{9,15}") || name.isBlank() || name.length()>150) throw new BusinessException(CommonErrorCode.BAD_REQUEST,"Bổ sung họ tên và số điện thoại hợp lệ vào hồ sơ Supabase.");
  if(users.existsByPhone(phone)) throw new BusinessException(CommonErrorCode.CONFLICT,"Số điện thoại đã thuộc hồ sơ khác. Liên hệ quản trị viên để liên kết tài khoản.");
  UserEntity user=new UserEntity();user.setId(authId);user.setSupabaseUserId(authId);user.setPhone(phone);user.setEmail(email);user.setFullName(name);user.setPasswordHash("!SUPABASE_ONLY");
  user.setRoleId(roles.findByCode("STUDENT").orElseThrow().getId());user.setIsActive(true);
  String gender=meta.path("gender").asText("");if(java.util.Set.of("MALE","FEMALE","OTHER").contains(gender)) user.setGender(gender);
  String dob=meta.path("dob").asText("");if(!dob.isBlank()){try{user.setDob(java.time.LocalDate.parse(dob));}catch(java.time.format.DateTimeParseException ex){throw new BusinessException(CommonErrorCode.BAD_REQUEST);}}
  return users.saveAndFlush(user);
 }
}
