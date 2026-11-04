package com.company.yoga.branch.facility.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class RoomDto {

    private RoomDto() {}

    public record RoomResponse(
        UUID id,
        UUID branchId,
        String name,
        String floor,
        Integer maxCapacity,
        Boolean isActive,
        String roomType,
        String liveHostAccount
    ) {}

    public record CreateRoomRequest(
        @NotBlank(message = "Tên phòng tập không được để trống")
        String name,

        String floor,

        @NotNull(message = "Sức chứa tối đa không được để trống")
        @Min(value = 1, message = "Sức chứa phòng phải lớn hơn 0")
        Integer maxCapacity,

        Boolean isActive,

        @jakarta.validation.constraints.Size(max = 150, message = "Tài khoản host tối đa 150 ký tự")
        String liveHostAccount,

        /** PHYSICAL (default) or VIRTUAL (online room = one Zoom licence). Fixed after creation. */
        @jakarta.validation.constraints.Pattern(regexp = "PHYSICAL|VIRTUAL", message = "Loại phòng không hợp lệ")
        String roomType
    ) {}

    public record UpdateRoomRequest(
        @NotBlank(message = "Tên phòng tập không được để trống")
        String name,

        String floor,

        @NotNull(message = "Sức chứa tối đa không được để trống")
        @Min(value = 1, message = "Sức chứa phòng phải lớn hơn 0")
        Integer maxCapacity,

        Boolean isActive,

        @jakarta.validation.constraints.Size(max = 150, message = "Tài khoản host tối đa 150 ký tự")
        String liveHostAccount
    ) {}
}
