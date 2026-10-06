package com.company.yoga.identity.account.service;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.company.yoga.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
class SupabaseAuthGatewayTest {
 private MockRestServiceServer server;private SupabaseAuthGateway gateway;
 @BeforeEach void setup(){var builder=RestClient.builder().baseUrl("https://project.supabase.co/auth/v1").defaultHeader("apikey","public-key");server=MockRestServiceServer.bindTo(builder).build();gateway=new SupabaseAuthGateway(builder.build(),"");}
 @Test void verifiesTokenAgainstSupabase(){server.expect(requestTo("https://project.supabase.co/auth/v1/user")).andExpect(header("Authorization","Bearer access-token")).andRespond(withSuccess("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"email_confirmed_at\":\"2026-10-03\"}",MediaType.APPLICATION_JSON));assertThat(gateway.verifiedUser("access-token").path("id").asText()).endsWith("1");server.verify();}
 @Test void invalidTokenIsRejected(){server.expect(requestTo("https://project.supabase.co/auth/v1/user")).andRespond(withUnauthorizedRequest());assertThatThrownBy(()->gateway.verifiedUser("old-local-jwt")).isInstanceOf(BusinessException.class);}
 @Test void anonymousUserIsRejected(){server.expect(requestTo("https://project.supabase.co/auth/v1/user")).andRespond(withSuccess("{\"id\":\"anonymous\",\"is_anonymous\":true}",MediaType.APPLICATION_JSON));assertThatThrownBy(()->gateway.verifiedUser("token")).isInstanceOf(BusinessException.class);}
 @Test void unconfirmedEmailIsRejected(){server.expect(requestTo("https://project.supabase.co/auth/v1/user")).andRespond(withSuccess("{\"id\":\"unconfirmed\"}",MediaType.APPLICATION_JSON));assertThatThrownBy(()->gateway.verifiedUser("token")).isInstanceOf(BusinessException.class);}
 @Test void adminCreationRequiresPrivateKey(){assertThatThrownBy(()->gateway.createUser("staff@example.com","private-password","Staff","0909111222")).isInstanceOf(BusinessException.class);}
}
