package com.company.yoga.identity.notification;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Thông báo", description = "Thông báo trong app (chuông) và nhắc gia hạn")
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @Operation(summary = "Thông báo mới nhất của người đang đăng nhập và số chưa đọc")
    @GetMapping("/api/v1/me/notifications")
    public ApiResponse<NotificationService.Inbox> mine(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(notifications.mine(limit));
    }

    @PostMapping("/api/v1/me/notifications/{id}/read")
    public ApiResponse<Void> read(@PathVariable UUID id) {
        notifications.markRead(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/api/v1/me/notifications/read-all")
    public ApiResponse<Void> readAll() {
        notifications.markAllRead();
        return ApiResponse.success(null);
    }
}
