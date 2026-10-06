package com.company.yoga.membership.plan.dto;

import com.company.yoga.membership.plan.entity.MembershipEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class MembershipDto {

    private MembershipDto() {}

    public record Resp(
            UUID id,
            String membershipCode,
            UUID studentId,
            UUID planId,
            UUID registeredBranchId,
            Boolean isAllBranches,
            LocalDate startDate,
            LocalDate endDate,
            Integer totalSessions,
            Integer remainingSessions,
            String status,
            UUID sourceOrderItemId,
            BigDecimal purchasedPrice,
            String notes,
            Instant createdAt
    ) {
        public static Resp from(MembershipEntity entity) {
            return new Resp(
                    entity.getId(),
                    entity.getMembershipCode(),
                    entity.getStudentId(),
                    entity.getPlanId(),
                    entity.getRegisteredBranchId(),
                    entity.getIsAllBranches(),
                    entity.getStartDate(),
                    entity.getEndDate(),
                    entity.getTotalSessions(),
                    entity.getRemainingSessions(),
                    entity.getStatus(),
                    entity.getSourceOrderItemId(),
                    entity.getPurchasedPrice(),
                    entity.getNotes(),
                    entity.getCreatedAt()
            );
        }
    }
}
