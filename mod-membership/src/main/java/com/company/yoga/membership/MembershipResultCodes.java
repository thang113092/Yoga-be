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
    CHECKOUT_NOT_READY("CHECKOUT_503", 503, "Chức năng cấp và đổi thẻ chưa sẵn sàng. Vui lòng liên hệ quản lý để hoàn tất cập nhật hệ thống.", "membership.checkout-not-ready"),
    ORDER_NOT_FOUND("ORDER_404", 404, "Không tìm thấy đơn hàng", "order.not-found"),
    ORDER_ALREADY_PAID("ORDER_409", 409, "Đơn hàng đã được thanh toán", "order.already-paid"),
    PAYMENT_DUPLICATE_IDEMPOTENCY("PAY_409", 409, "Yêu cầu thanh toán trùng lặp (Idempotency Key đã được xử lý)", "payment.duplicate"),
    INVALID_PAYMENT_AMOUNT("PAY_422", 422, "Số tiền thanh toán không khớp với giá trị đơn hàng", "payment.invalid-amount"),
    STUDENT_NOT_FOUND("STUDENT_404", 404, "Không tìm thấy học viên trong hệ thống", "student.not-found"),
    ONLINE_PAYMENT_NOT_FOUND("ONLINE_PAY_404", 404, "Không tìm thấy giao dịch thanh toán online", "online-payment.not-found"),
    ONLINE_PAYMENT_UNAVAILABLE("ONLINE_PAY_503", 503, "Thanh toán online chưa được cấu hình. Vui lòng dùng phương thức khác.", "online-payment.unavailable"),
    ONLINE_PAYMENT_GATEWAY_ERROR("ONLINE_PAY_502", 502, "Không kết nối được cổng thanh toán. Vui lòng thử lại.", "online-payment.gateway-error"),
    ONLINE_PAYMENT_WINDOW_CLOSED("ONLINE_PAY_410", 409, "Đơn đã qua ngày hoặc sắp hết hạn giữ chỗ. Vui lòng hủy đơn và tạo lại.", "online-payment.window-closed"),
    ONLINE_PAYMENT_PENDING("ONLINE_PAY_409", 409, "Đơn đang có mã VietQR chờ thanh toán. Hủy mã trước khi thu bằng phương thức khác.", "online-payment.pending");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
