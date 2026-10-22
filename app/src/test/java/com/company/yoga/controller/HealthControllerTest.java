package com.company.yoga.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.plan.repository.MembershipPlanRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private MembershipPlanRepository membershipPlanRepository;

    private HealthController healthController;

    @BeforeEach
    void setUp() {
        healthController = new HealthController(membershipPlanRepository);
    }

    @Test
    @DisplayName("checkHealth() trả về 200 OK khi CSDL kết nối thành công và đếm được dữ liệu")
    void checkHealth_DatabaseUp_ReturnsOk() {
        when(membershipPlanRepository.count()).thenReturn(5L);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = healthController.checkHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).containsEntry("status", "UP");
        assertThat(response.getBody().getData()).containsEntry("database", "UP");
        assertThat(response.getBody().getData()).containsEntry("checkedTable", "yoga.membership_plans");
        assertThat(response.getBody().getData()).containsEntry("recordCount", 5L);
    }

    @Test
    @DisplayName("checkHealth() trả về 503 Service Unavailable khi CSDL mất kết nối")
    void checkHealth_DatabaseDown_ReturnsServiceUnavailable() {
        when(membershipPlanRepository.count()).thenThrow(new DataAccessResourceFailureException("Connection refused"));

        ResponseEntity<ApiResponse<Map<String, Object>>> response = healthController.checkHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo("503");
        assertThat(response.getBody().getMessage()).contains("Connection refused");
    }
}
