package com.company.yoga.schedule.booking.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.schedule.booking.dto.CheckInDto;
import com.company.yoga.schedule.booking.service.CheckInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Điểm danh (Check-In)", description = "APIs quét QR điểm danh 1 chạm tại quầy/cửa phòng")
@RestController
@RequestMapping("/api/v1/check-in")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST', 'INSTRUCTOR')")
public class CheckInController {

    private final CheckInService checkInService;

    @Operation(summary = "Thực hiện điểm danh (Cửa sổ 30 phút trước ca, chống trùng lặp)")
    @PostMapping
    public ApiResponse<CheckInDto.CheckInResp> checkIn(@Valid @RequestBody CheckInDto.CheckInReq req) {
        CheckInDto.CheckInResp resp = checkInService.processCheckIn(req);
        return ApiResponse.success(resp);
    }
}
