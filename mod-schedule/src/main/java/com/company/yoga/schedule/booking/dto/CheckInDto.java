package com.company.yoga.schedule.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class CheckInDto {

    private CheckInDto() {}

    public record CheckInReq(
            UUID bookingId,
            String bookingCode,
            UUID studentId,
            UUID scheduleId,
            UUID branchId,
            UUID checkedInBy,
            String checkInMethod,
            String notes
    ) {
        public CheckInReq(UUID bookingId, UUID studentId, UUID scheduleId, UUID branchId, UUID checkedInBy, String checkInMethod, String notes) {
            this(bookingId, null, studentId, scheduleId, branchId, checkedInBy, checkInMethod, notes);
        }
    }

    public record QuickScanReq(
            @NotNull(message = "Dữ liệu mã QR không được để trống")
            String qrData,

            UUID branchId,
            UUID checkedInBy
    ) {}

    public record CheckInResp(
            UUID attendanceId,
            UUID bookingId,
            String bookingCode,
            UUID studentId,
            String studentName,
            String className,
            String branchName,
            Instant checkedInAt,
            String attendanceStatus,
            Integer matNumber,
            boolean alreadyCheckedIn
    ) {}
}
