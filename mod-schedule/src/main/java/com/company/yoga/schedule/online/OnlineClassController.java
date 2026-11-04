package com.company.yoga.schedule.online;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Lớp học online", description = "Vào lớp livestream, màn HLV và webhook Zoom")
@RestController
@RequiredArgsConstructor
@Slf4j
public class OnlineClassController {

    private final OnlineClassService online;
    private final ZoomWebhookService webhooks;

    @Operation(summary = "Học viên vào lớp online: ghi điểm danh và trả link Zoom riêng (mở trước giờ học 15 phút)")
    @PostMapping("/api/v1/me/bookings/{bookingId}/online-join")
    public ApiResponse<OnlineDto.JoinResp> join(@PathVariable UUID bookingId) {
        return ApiResponse.success(online.join(bookingId));
    }

    @Operation(summary = "Trạng thái phòng Zoom của buổi online; link host cho HLV/quản lý từ 1 giờ trước giờ học")
    @GetMapping("/api/v1/online-sessions/{scheduleId}")
    public ApiResponse<OnlineDto.SessionResp> session(@PathVariable UUID scheduleId) {
        return ApiResponse.success(online.session(scheduleId));
    }

    @Operation(summary = "Đồng bộ lại phòng Zoom ngay (bỏ qua thời gian chờ thử lại)")
    @PostMapping("/api/v1/online-sessions/{scheduleId}/sync")
    public ApiResponse<OnlineDto.SessionResp> resync(@PathVariable UUID scheduleId) {
        return ApiResponse.success(online.resync(scheduleId));
    }

    /** Public endpoint: trust comes only from the Zoom signature. */
    @Operation(summary = "Webhook Zoom (xác thực bằng chữ ký HMAC)")
    @PostMapping("/api/v1/integration-webhooks/zoom")
    public ResponseEntity<Map<String, Object>> zoom(@RequestHeader(value = "x-zm-request-timestamp", required = false) String timestamp,
                                                    @RequestHeader(value = "x-zm-signature", required = false) String signature,
                                                    @RequestBody String body) {
        return switch (webhooks.handle(timestamp, signature, body)) {
            case ZoomWebhookService.Rejected r -> {
                log.warn("Rejected Zoom webhook with an invalid signature");
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
            }
            case ZoomWebhookService.Challenge c ->
                    ResponseEntity.ok(Map.of("plainToken", c.plainToken(), "encryptedToken", c.encryptedToken()));
            case ZoomWebhookService.Accepted a -> ResponseEntity.ok(Map.of("success", true));
        };
    }
}
