package com.company.yoga.schedule.booking.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.schedule.booking.dto.WaitlistDto;
import com.company.yoga.schedule.booking.service.WaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Hàng đợi lớp học (Waitlists)", description = "APIs đăng ký hàng chờ khi lớp học kín chỗ & tự động đôn lên")
@RestController
@RequestMapping("/api/v1/waitlists")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @Operation(summary = "Đăng ký vào hàng chờ khi ca học đã kín chỗ")
    @PostMapping
    public ApiResponse<WaitlistDto.Resp> joinWaitlist(@Valid @RequestBody WaitlistDto.JoinReq req) {
        WaitlistDto.Resp resp = waitlistService.joinWaitlist(req);
        return ApiResponse.success(resp);
    }

    @Operation(summary = "Rút khỏi hàng chờ")
    @PostMapping("/{id}/cancel")
    public ApiResponse<WaitlistDto.Resp> cancelWaitlist(
            @PathVariable UUID id,
            @RequestParam UUID studentId
    ) {
        WaitlistDto.Resp resp = waitlistService.cancelWaitlist(id, studentId);
        return ApiResponse.success(resp);
    }

    @Operation(summary = "Lấy danh sách các ca học đang trong hàng chờ của học viên")
    @GetMapping("/student/{studentId}")
    public ApiResponse<List<WaitlistDto.StudentWaitlistResp>> getStudentWaitlists(@PathVariable UUID studentId) {
        List<WaitlistDto.StudentWaitlistResp> list = waitlistService.getStudentWaitlists(studentId);
        return ApiResponse.success(list);
    }

    @Operation(summary = "Lấy số lượng người đang đợi của một ca học")
    @GetMapping("/schedule/{scheduleId}/count")
    public ApiResponse<Long> getWaitingCount(@PathVariable UUID scheduleId) {
        long count = waitlistService.getWaitingCount(scheduleId);
        return ApiResponse.success(count);
    }
}
