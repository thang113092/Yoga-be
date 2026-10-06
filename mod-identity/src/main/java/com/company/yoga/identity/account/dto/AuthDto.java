package com.company.yoga.identity.account.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public class AuthDto {

    public record CurrentUserResponse(
        UUID id,
        String phone,
        String email,
        String fullName,
        String roleCode,
        String roleName,
        UUID homeBranchId,
        Boolean isActive
    ) {}
}
