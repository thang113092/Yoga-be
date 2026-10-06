package com.company.yoga.identity.account.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.common.util.SecurityUtils;
import com.company.yoga.identity.IdentityResultCodes;
import com.company.yoga.identity.account.dto.AuthDto.CurrentUserResponse;
import com.company.yoga.identity.account.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth API", description = "Hồ sơ và quyền nghiệp vụ của tài khoản Supabase")
public class AuthController {

    private final com.company.yoga.identity.account.service.AccessPolicy accessPolicy;
    private final com.company.yoga.identity.account.repository.UserBranchRepository assignments;

    @GetMapping("/branches")
    public ApiResponse<java.util.List<UUID>> assignedBranches() {
        var actor = accessPolicy.actor();
        var ids = new java.util.HashSet<UUID>(assignments.branchIdsForUser(actor.getId()));
        if (actor.getHomeBranchId() != null) ids.add(actor.getHomeBranchId());
        return ApiResponse.success(java.util.List.copyOf(ids));
    }

    private final AuthService authService;

    @GetMapping("/me")
    @Operation(summary = "Lấy thông tin tài khoản hiện tại từ Token")
    public ApiResponse<CurrentUserResponse> getCurrentUser() {
        UUID currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.INVALID_CREDENTIALS));
        return ApiResponse.success(authService.getCurrentUser(currentUserId));
    }
}
