package com.company.yoga.schedule.booking.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.service.BookingService;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Đặt chỗ (Bookings)", description = "APIs đặt chỗ ca học, giữ lượt thẻ tập và hủy ca hoàn lượt")
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @Operation(summary = "Đặt chỗ cho ca học (Khóa bi quan chống overbooking & giữ lượt thẻ)")
    @PostMapping
    public ApiResponse<BookingDto.Resp> createBooking(@Valid @RequestBody BookingDto.CreateReq req) {
        BookingDto.Resp resp = bookingService.createBooking(req);
        return ApiResponse.success(resp);
    }

    @Operation(summary = "Hủy đặt chỗ (Chỉ trước giờ học - tự động hoàn lượt thẻ tập)")
    @PostMapping("/{id}/cancel")
    public ApiResponse<BookingDto.Resp> cancelBooking(
            @PathVariable UUID id,
            @RequestBody(required = false) BookingDto.CancelReq req
    ) {
        String reason = req != null ? req.reason() : null;
        BookingDto.Resp resp = bookingService.cancelBooking(id, reason);
        return ApiResponse.success(resp);
    }

    @Operation(summary = "Lấy danh sách các ca đã đặt của học viên")
    @GetMapping("/student/{studentId}")
    public ApiResponse<List<BookingDto.Resp>> getStudentBookings(@PathVariable UUID studentId) {
        List<BookingDto.Resp> list = bookingService.getStudentBookings(studentId);
        return ApiResponse.success(list);
    }

    @Operation(summary = "Lấy danh sách chi tiết các ca đã đặt của học viên kèm thông tin lớp và quyền hủy")
    @GetMapping("/student/{studentId}/details")
    public ApiResponse<List<BookingDto.StudentBookingDetailResp>> getStudentBookingDetails(@PathVariable UUID studentId) {
        List<BookingDto.StudentBookingDetailResp> list = bookingService.getStudentBookingDetails(studentId);
        return ApiResponse.success(list);
    }
}
