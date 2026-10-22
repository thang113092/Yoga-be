package com.company.yoga.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.plan.repository.MembershipPlanRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Health Check", description = "Public health check endpoint cho UptimeRobot và giám sát hệ thống")
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final MembershipPlanRepository membershipPlanRepository;

    @RequestMapping(
            value = {"/health", "/api/v1/health"},
            method = {RequestMethod.GET, RequestMethod.HEAD}
    )
    @Operation(summary = "Kiểm tra sức khỏe hệ thống và kết nối CSDL (Public cho UptimeRobot / Ping)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkHealth() {
        try {
            long planCount = membershipPlanRepository.count();
            Map<String, Object> healthData = Map.of(
                    "status", "UP",
                    "database", "UP",
                    "checkedTable", "yoga.membership_plans",
                    "recordCount", planCount,
                    "timestamp", Instant.now()
            );
            return ResponseEntity.ok(ApiResponse.success(healthData, "Hệ thống và CSDL hoạt động bình thường"));
        } catch (Exception ex) {
            log.error("Health check thất bại khi truy vấn cơ sở dữ liệu: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.failure("503", "Kết nối cơ sở dữ liệu không khả dụng: " + ex.getMessage()));
        }
    }
}
