package com.company.yoga.schedule.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public final class ClassTypeDto {

    private ClassTypeDto() {}

    public record CreateReq(
            @NotBlank(message = "Mã loại lớp không được để trống")
            String code,

            @NotBlank(message = "Tên loại lớp không được để trống")
            String name,

            String description,

            @NotNull(message = "Thời lượng không được để trống")
            @Positive(message = "Thời lượng phải lớn hơn 0")
            Integer defaultDurationMinutes,

            @NotBlank(message = "Mức độ cường độ không được để trống")
            String intensityLevel
    ) {}

    public record Resp(
            UUID id,
            String code,
            String name,
            String description,
            Integer defaultDurationMinutes,
            String intensityLevel,
            Boolean isActive
    ) {}
}
