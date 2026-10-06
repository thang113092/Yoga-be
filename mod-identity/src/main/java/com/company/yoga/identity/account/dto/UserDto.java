package com.company.yoga.identity.account.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class UserDto {

    public record CreateUserRequest(
        @NotBlank(message = "Số điện thoại không được để trống")
        String phone,

        @NotBlank(message = "Mật khẩu không được để trống")
        @jakarta.validation.constraints.Size(min = 8, max = 72)
        String password,

        @NotBlank(message = "Họ và tên không được để trống")
        String fullName,

        @NotBlank(message = "Email không được để trống")
        @jakarta.validation.constraints.Email
        String email,
        String gender,
        LocalDate dob,

        @NotBlank(message = "Mã vai trò không được để trống")
        String roleCode,

        UUID homeBranchId
    ) {}

    public record UpdateStatusRequest(
        @jakarta.validation.constraints.NotNull(message = "Trạng thái không được để trống")
        Boolean isActive
    ) {}

    public record UserResponse(
        UUID id,
        String phone,
        String email,
        String fullName,
        String gender,
        LocalDate dob,
        UUID roleId,
        String roleCode,
        String roleName,
        UUID homeBranchId,
        String branchName,
        Boolean isActive,
        Instant createdAt
    ) {}
}
