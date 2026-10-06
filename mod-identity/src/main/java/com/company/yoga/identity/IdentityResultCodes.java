package com.company.yoga.identity;

import com.company.yoga.common.api.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IdentityResultCodes implements ErrorCode {

    USER_NOT_FOUND("USER_404", 404, "Không tìm thấy người dùng", "user.not-found"),
    USER_INACTIVE("USER_403", 403, "Tài khoản hiện đang bị khóa hoặc chưa kích hoạt", "user.inactive"),
    INVALID_CREDENTIALS("AUTH_401", 401, "Số điện thoại hoặc mật khẩu không chính xác", "auth.invalid-credentials"),
    PHONE_ALREADY_EXISTS("USER_409", 409, "Số điện thoại đã được đăng ký", "user.phone-exists"),
    EMAIL_ALREADY_EXISTS("USER_409_EMAIL", 409, "Địa chỉ email đã được đăng ký", "user.email-exists"),
    ROLE_NOT_FOUND("ROLE_404", 404, "Không tìm thấy vai trò người dùng", "role.not-found"),
    FORBIDDEN_ACTION("AUTH_403", 403, "Bạn không có quyền thực hiện thao tác này", "auth.forbidden"),
    INVALID_ROLE_ASSIGNMENT("ROLE_403_ASSIGN", 403, "Bạn không có quyền tạo hoặc gán vai trò này", "role.invalid-assignment"),
    INVALID_BRANCH_ASSIGNMENT("BRANCH_403_ASSIGN", 403, "Bạn chỉ có thể tạo tài khoản cho chi nhánh do mình quản lý", "branch.invalid-assignment");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
