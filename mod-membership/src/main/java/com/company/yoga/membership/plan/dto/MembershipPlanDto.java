package com.company.yoga.membership.plan.dto;

import com.company.yoga.membership.plan.entity.MembershipPlanEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MembershipPlanDto {

    private MembershipPlanDto() {}

    public record CreateReq(
            @NotBlank(message = "Mã gói tập không được để trống")
            String code,

            @NotBlank(message = "Tên gói tập không được để trống")
            String name,

            String description,

            @NotNull(message = "Giá gói tập không được để trống")
            @PositiveOrZero(message = "Giá gói tập không được âm")
            BigDecimal price,

            @NotBlank(message = "Loại gói tập không được để trống")
            String planType,

            @jakarta.validation.constraints.Positive
            Integer durationDays,
            @jakarta.validation.constraints.Positive
            Integer totalSessions,
            Boolean isAllBranches
    ) {}

    public record UpdateReq(
            @NotBlank(message = "Tên gói tập không được để trống")
            String name,

            String description,

            @NotNull(message = "Giá gói tập không được để trống")
            @PositiveOrZero(message = "Giá gói tập không được âm")
            BigDecimal price,

            Boolean isAllBranches,
            Boolean isActive
    ) {}

    public record Resp(
            UUID id,
            String code,
            String name,
            String description,
            BigDecimal price,
            String planType,
            Integer durationDays,
            Integer totalSessions,
            Boolean isAllBranches,
            Boolean isActive,
            Instant createdAt
    ) {
        public static Resp from(MembershipPlanEntity entity) {
            return new Resp(
                    entity.getId(),
                    entity.getCode(),
                    entity.getName(),
                    entity.getDescription(),
                    entity.getPrice(),
                    entity.getPlanType(),
                    entity.getDurationDays(),
                    entity.getTotalSessions(),
                    entity.getIsAllBranches(),
                    entity.getIsActive(),
                    entity.getCreatedAt()
            );
        }
    }
}
