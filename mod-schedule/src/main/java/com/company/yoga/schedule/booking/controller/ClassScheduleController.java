package com.company.yoga.schedule.booking.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Lịch học (Schedules)", description = "APIs tra cứu và quản lý lịch học ca tập các chi nhánh")
@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ClassScheduleController {

    private final ClassScheduleService scheduleService;

    @Operation(summary = "Lấy danh sách ca học theo chi nhánh và khoảng thời gian")
    @GetMapping
    public ApiResponse<List<ScheduleDto.Resp>> getSchedules(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end
    ) {
        List<ScheduleDto.Resp> list = scheduleService.getSchedulesByBranch(branchId, start, end);
        return ApiResponse.success(list);
    }

    @Operation(summary = "Xem chi tiết một ca học")
    @GetMapping("/{id}")
    public ApiResponse<ScheduleDto.Resp> getScheduleById(@PathVariable UUID id) {
        ScheduleDto.Resp resp = scheduleService.getScheduleById(id);
        return ApiResponse.success(resp);
    }

    @Operation(summary = "Tạo mới ca học")
    @PostMapping
    public ApiResponse<ScheduleDto.Resp> createSchedule(@Valid @RequestBody ScheduleDto.CreateReq req) {
        ScheduleDto.Resp resp = scheduleService.createSchedule(req);
        return ApiResponse.success(resp);
    }

    @Operation(summary = "Hủy ca học")
    @org.springframework.web.bind.annotation.PutMapping("/{id}/cancel")
    public ApiResponse<ScheduleDto.Resp> cancelSchedule(@PathVariable UUID id) {
        ScheduleDto.Resp resp = scheduleService.cancelSchedule(id);
        return ApiResponse.success(resp, "Hủy ca học thành công");
    }

    @Operation(summary = "Danh sách học viên đăng ký của ca học")
    @GetMapping("/{id}/attendees")
    public ApiResponse<List<com.company.yoga.schedule.booking.dto.BookingDto.AttendeeResp>> getScheduleAttendees(@PathVariable UUID id) {
        List<com.company.yoga.schedule.booking.dto.BookingDto.AttendeeResp> list = scheduleService.getScheduleAttendees(id);
        return ApiResponse.success(list);
    }
}
