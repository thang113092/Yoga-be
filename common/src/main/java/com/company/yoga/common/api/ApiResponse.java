package com.company.yoga.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Vỏ phản hồi API tiêu chuẩn")
public class ApiResponse<T> {

    @Schema(description = "Mã kết quả hoặc mã lỗi nghiệp vụ", example = "200")
    private String code;

    @Schema(description = "Trạng thái thành công", example = "true")
    private boolean success;

    @Schema(description = "Thông điệp mô tả", example = "Thao tác thành công")
    private String message;

    @Schema(description = "Dữ liệu trả về")
    private T data;

    @Schema(description = "Thời điểm phản hồi (UTC)")
    private Instant timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("200", true, "Thành công", data, Instant.now());
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("200", true, message, data, Instant.now());
    }

    public static <T> ApiResponse<T> success(T data, ErrorCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), true, resultCode.getMessage(), data, Instant.now());
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), false, errorCode.getMessage(), null, Instant.now());
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String customMessage) {
        return new ApiResponse<>(errorCode.getCode(), false, customMessage, null, Instant.now());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(code, false, message, null, Instant.now());
    }
}
