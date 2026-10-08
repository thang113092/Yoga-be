package com.company.yoga.schedule.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class ScheduleDto {

    private ScheduleDto() {}

    public record Resp(
            UUID id,
            UUID branchId,
            String branchName,
            UUID roomId,
            UUID instructorId,
            String className,
            String instructorName,
            String roomName,
            Instant startTime,
            Instant endTime,
            Integer maxCapacity,
            Integer bookedCount,
            Integer availableSlots,
            String status,
            UUID courseClassId,
            Integer sessionNumber
    ) {
        public Resp(UUID id, UUID branchId, String branchName, UUID roomId, UUID instructorId,
                    String className, String instructorName, String roomName, Instant startTime, Instant endTime,
                    Integer maxCapacity, Integer bookedCount, Integer availableSlots, String status) {
            this(id,branchId,branchName,roomId,instructorId,className,instructorName,roomName,startTime,endTime,
                    maxCapacity,bookedCount,availableSlots,status,null,null);
        }
    }

    public record CreateReq(
            @NotNull(message = "Chi nhánh không được để trống")
            UUID branchId,

            @NotNull(message = "Phòng tập không được để trống")
            UUID roomId,

            @NotNull(message = "Loại lớp không được để trống")
            UUID classTypeId,

            @NotNull(message = "Huấn luyện viên không được để trống")
            UUID instructorId,

            @NotNull(message = "Thời gian bắt đầu không được để trống")
            Instant startTime,

            @NotNull(message = "Thời gian kết thúc không được để trống")
            Instant endTime,

            @NotNull(message = "Sĩ số tối đa không được để trống")
            Integer maxCapacity
    ) {}
}
