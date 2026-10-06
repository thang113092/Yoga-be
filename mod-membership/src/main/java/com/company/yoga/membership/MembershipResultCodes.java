package com.company.yoga.membership;

import com.company.yoga.common.api.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MembershipResultCodes implements ErrorCode {

    PLAN_NOT_FOUND("PLAN_404", 404, "Không tìm thấy gói tập", "plan.not-found"),
    PLAN_CODE_EXISTS("PLAN_409", 409, "Mã gói tập đã tồn tại", "plan.code-exists"),
    MEMBERSHIP_NOT_FOUND("MEMBERSHIP_404", 404, "Không tìm thấy thẻ tập của học viên", "membership.not-found"),
    MEMBERSHIP_INACTIVE("MEMBERSHIP_403", 403, "Thẻ tập chưa kích hoạt hoặc đã hết hạn", "membership.inactive"),
    INSUFFICIENT_SESSIONS("MEMBERSHIP_422", 422, "Thẻ tập đã hết lượt khả dụng", "membership.out-of-sessions"),
    ORDER_NOT_FOUND("ORDER_404", 404, "Không tìm thấy đơn hàng", "order.not-found"),
    ORDER_ALREADY_PAID("ORDER_409", 409, "Đơn hàng đã được thanh toán", "order.already-paid"),
    PAYMENT_DUPLICATE_IDEMPOTENCY("PAY_409", 409, "Yêu cầu thanh toán trùng lặp (Idempotency Key đã được xử lý)", "payment.duplicate"),
    INVALID_PAYMENT_AMOUNT("PAY_422", 422, "Số tiền thanh toán không khớp với giá trị đơn hàng", "payment.invalid-amount"),
    STUDENT_NOT_FOUND("STUDENT_404", 404, "Không tìm thấy học viên trong hệ thống", "student.not-found");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
