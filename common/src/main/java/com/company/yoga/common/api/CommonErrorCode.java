package com.company.yoga.common.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    SUCCESS("SUCCESS", 200, "Thành công", "sys.success"),
    BAD_REQUEST("BAD_REQUEST", 400, "Yêu cầu không hợp lệ", "sys.bad-request"),
    UNAUTHORIZED("UNAUTHORIZED", 401, "Yêu cầu chưa được xác thực", "sys.unauthorized"),
    FORBIDDEN("FORBIDDEN", 403, "Không có quyền thực hiện thao tác này", "sys.forbidden"),
    NOT_FOUND("NOT_FOUND", 404, "Không tìm thấy tài nguyên", "sys.not-found"),
    CONFLICT("CONFLICT", 409, "Dữ liệu bị xung đột hoặc đã tồn tại", "sys.conflict"),
    VALIDATION_FAILED("VALIDATION_FAILED", 422, "Dữ liệu kiểm tra không hợp lệ", "sys.validation-failed"),
    INTERNAL_SERVER_ERROR("INTERNAL_ERROR", 500, "Lỗi máy chủ nội bộ", "sys.internal-error");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
