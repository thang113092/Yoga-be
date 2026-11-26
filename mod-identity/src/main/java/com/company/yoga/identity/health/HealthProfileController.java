package com.company.yoga.identity.health;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Hồ sơ sức khỏe", description = "Học viên tự khai tình trạng sức khỏe để HLV lưu ý và để lọc video phù hợp")
@RestController
@RequiredArgsConstructor
public class HealthProfileController {

    private final HealthProfileService service;

    @Operation(summary = "Hồ sơ sức khỏe của tôi (học viên)")
    @GetMapping("/api/v1/me/health-profile")
    public ApiResponse<HealthProfileService.Profile> mine() {
        return ApiResponse.success(service.mine());
    }

    @Operation(summary = "Lưu hồ sơ sức khỏe của tôi (học viên)")
    @PutMapping("/api/v1/me/health-profile")
    public ApiResponse<HealthProfileService.Profile> save(@Valid @RequestBody HealthProfileService.SaveReq req) {
        return ApiResponse.success(service.save(req));
    }
}
