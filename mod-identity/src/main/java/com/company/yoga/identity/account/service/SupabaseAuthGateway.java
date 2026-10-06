package com.company.yoga.identity.account.service;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
@Service
public class SupabaseAuthGateway {
 private final RestClient client; private final String adminKey;
 @Autowired
 public SupabaseAuthGateway(@Value("${supabase.url}") String url, @Value("${supabase.publishable-key}") String key, @Value("${supabase.admin-key:}") String adminKey) {
  this(buildClient(url,key),adminKey);
 }
 private static RestClient buildClient(String url,String key){
  var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build());
  factory.setReadTimeout(java.time.Duration.ofSeconds(10));
  return RestClient.builder().baseUrl(url+"/auth/v1").defaultHeader("apikey",key).requestFactory(factory).build();
 }
 SupabaseAuthGateway(RestClient client,String adminKey){this.client=client;this.adminKey=adminKey;}
 public JsonNode verifiedUser(String token) {
  try {
   JsonNode user=client.get().uri("/user").header("Authorization","Bearer "+token).retrieve().body(JsonNode.class);
   if(user==null || user.path("id").asText().isBlank() || user.path("is_anonymous").asBoolean(false)) throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
   if(user.path("email_confirmed_at").isMissingNode() || user.path("email_confirmed_at").isNull()) throw new BusinessException(CommonErrorCode.FORBIDDEN,"Vui lòng xác nhận email Supabase.");
   return user;
  } catch(RestClientResponseException ex) {
   if(ex.getStatusCode().is4xxClientError()) throw new BusinessException(CommonErrorCode.UNAUTHORIZED,"Phiên Supabase không hợp lệ hoặc đã hết hạn.");
   throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR,"Không kết nối được dịch vụ xác thực Supabase.");
  } catch(org.springframework.web.client.ResourceAccessException ex) { throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR,"Không kết nối được dịch vụ xác thực Supabase."); }
 }
 public UUID createUser(String email,String password,String fullName,String phone) {
  if(adminKey.isBlank()) throw new BusinessException(CommonErrorCode.BAD_REQUEST,"Cần cấu hình SUPABASE_SERVICE_ROLE_KEY ở backend để tạo tài khoản nhân sự.");
  try {
   JsonNode user=client.post().uri("/admin/users").header("apikey",adminKey).header("Authorization","Bearer "+adminKey)
    .body(Map.of("email",email,"password",password,"email_confirm",true,"user_metadata",Map.of("full_name",fullName,"phone",phone)))
    .retrieve().body(JsonNode.class);
   if(user==null || !user.hasNonNull("id")) throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
   return UUID.fromString(user.get("id").asText());
  } catch(RestClientResponseException ex) { throw new BusinessException(ex.getStatusCode().is4xxClientError()?CommonErrorCode.CONFLICT:CommonErrorCode.INTERNAL_SERVER_ERROR,"Không tạo được tài khoản Supabase. Kiểm tra email đã tồn tại và cấu hình quản trị."); }
 }
 public void removeCreatedUser(UUID id) {
  client.delete().uri("/admin/users/"+id).header("apikey",adminKey).header("Authorization","Bearer "+adminKey).retrieve().toBodilessEntity();
 }
}
