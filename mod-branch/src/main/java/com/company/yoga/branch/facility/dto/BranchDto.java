package com.company.yoga.branch.facility.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public class BranchDto {

    public record CreateBranchRequest(
        @NotBlank(message = "Mã chi nhánh không được để trống")
        String code,

        @NotBlank(message = "Tên chi nhánh không được để trống")
        String name,

        @NotBlank(message = "Địa chỉ không được để trống")
        String address,

        @NotBlank(message = "Số điện thoại không được để trống")
        String phone,

        String email,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolder
    ) {}

    public record UpdateBranchRequest(
        String code,

        @NotBlank(message = "Tên chi nhánh không được để trống")
        String name,

        @NotBlank(message = "Địa chỉ không được để trống")
        String address,

        @NotBlank(message = "Số điện thoại không được để trống")
        String phone,

        String email,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolder,
        Boolean isActive
    ) {}

    public record BranchResponse(
        UUID id,
        String code,
        String name,
        String address,
        String phone,
        String email,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolder,
        Boolean isActive,
        Instant createdAt,
        Long roomCount
    ) {}
}
