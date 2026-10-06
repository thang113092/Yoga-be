package com.company.yoga.identity.account.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.identity.account.dto.UserDto;
import com.company.yoga.identity.account.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User Management API", description = "Quản lý và phân quyền tài khoản (Super Admin & Branch Manager)")
public class UserController {

    private final UserService userService;

    @GetMapping("/students/lookup")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST')")
    public ApiResponse<UserDto.UserResponse> lookupStudent(@RequestParam String phone, @RequestParam UUID branchId) {
        return ApiResponse.success(userService.lookupStudent(phone, branchId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Tạo tài khoản mới (Super Admin tạo mọi role, Branch Manager chỉ tạo RECEPTIONIST/INSTRUCTOR/STUDENT thuộc chi nhánh của mình)")
    public ApiResponse<UserDto.UserResponse> createUser(@Valid @RequestBody UserDto.CreateUserRequest request) {
        UserDto.UserResponse resp = userService.createUser(request);
        return ApiResponse.success(resp, "Tạo tài khoản thành công");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Lấy danh sách người dùng theo phân quyền")
    public ApiResponse<List<UserDto.UserResponse>> getUsers(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String roleCode
    ) {
        List<UserDto.UserResponse> list = userService.getUsers(branchId, roleCode);
        return ApiResponse.success(list);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/status")
    @org.springframework.web.bind.annotation.PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    @Operation(summary = "Khóa hoặc mở khóa tài khoản người dùng")
    public ApiResponse<UserDto.UserResponse> updateUserStatus(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @Valid @RequestBody UserDto.UpdateStatusRequest request
    ) {
        UserDto.UserResponse resp = userService.updateUserStatus(id, request.isActive());
        String msg = Boolean.TRUE.equals(request.isActive()) ? "Mở khóa tài khoản thành công" : "Khóa tài khoản thành công";
        return ApiResponse.success(resp, msg);
    }
}
