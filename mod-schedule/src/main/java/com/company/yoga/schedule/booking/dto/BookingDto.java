package com.company.yoga.schedule.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class BookingDto {

    private BookingDto() {}

    public record CreateReq(
            @NotNull(message = "Mã ca học không được để trống")
            UUID scheduleId,

            @NotNull(message = "Mã học viên không được để trống")
            UUID studentId,

            @NotNull(message = "Mã thẻ tập không được để trống")
            UUID membershipId,

            @jakarta.validation.constraints.Positive
            Integer matNumber
    ) {}

    public record CancelReq(
            String reason
    ) {}

    public record Resp(
            UUID id,
            String bookingCode,
            UUID scheduleId,
            UUID studentId,
            UUID membershipId,
            Integer matNumber,
            String status,
            Instant bookingTime
    ) {}

    public record StudentBookingDetailResp(
            UUID id,
            String bookingCode,
            UUID scheduleId,
            String className,
            String instructorName,
            String roomName,
            String branchName,
            Instant startTime,
            Instant endTime,
            Integer matNumber,
            String status,
            Instant bookingTime,
            boolean canCancel
    ) {}

    public record AttendeeResp(
            UUID id,
            String bookingCode,
            UUID studentId,
            String studentName,
            String studentPhone,
            String studentEmail,
            String studentGender,
            Integer matNumber,
            String status,
            Instant bookingTime,
            Instant checkedInAt
    ) {
        public AttendeeResp(UUID id, String bookingCode, UUID studentId, String studentName, String studentPhone, Integer matNumber, String status, Instant bookingTime) {
            this(id, bookingCode, studentId, studentName, studentPhone, null, null, matNumber, status, bookingTime, null);
        }
    }

    public record WorkoutHistoryResp(
            UUID bookingId,
            String bookingCode,
            UUID scheduleId,
            String className,
            String intensityLevel,
            String instructorName,
            String roomName,
            String branchName,
            UUID branchId,
            Instant startTime,
            Instant endTime,
            Integer durationMinutes,
            Integer matNumber,
            String status,
            Instant checkedInAt,
            String checkInMethod,
            String attendanceStatus,
            String membershipCode,
            String notes
    ) {}
}
