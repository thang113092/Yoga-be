package com.company.yoga.schedule.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class WaitlistDto {

    private WaitlistDto() {}

    public record JoinReq(
            @NotNull(message = "Mã ca học không được để trống")
            UUID scheduleId,

            @NotNull(message = "Mã học viên không được để trống")
            UUID studentId,

            @NotNull(message = "Mã thẻ tập không được để trống")
            UUID membershipId
    ) {}

    public record Resp(
            UUID id,
            UUID scheduleId,
            UUID studentId,
            UUID membershipId,
            Integer queuePosition,
            String status,
            UUID convertedBookingId,
            Instant createdAt
    ) {}

    public record StudentWaitlistResp(
            UUID id,
            UUID scheduleId,
            String className,
            String instructorName,
            String roomName,
            String branchName,
            Instant startTime,
            Instant endTime,
            Integer queuePosition,
            String status,
            Instant createdAt
    ) {}
}
