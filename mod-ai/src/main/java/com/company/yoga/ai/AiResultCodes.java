package com.company.yoga.ai;

import com.company.yoga.common.api.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiResultCodes implements ErrorCode {

    DISABLED("AI_503_DISABLED", 503, "Tính năng AI chưa được bật. Vui lòng liên hệ quản trị", "ai.disabled"),
    QUOTA_EXCEEDED("AI_429_QUOTA", 429, "Bạn đã dùng hết lượt AI hôm nay. Vui lòng thử lại vào ngày mai", "ai.quota-exceeded"),
    BUDGET_EXCEEDED("AI_429_BUDGET", 429, "Tính năng AI tạm dừng vì đã đạt hạn mức chi phí hôm nay. Vui lòng thử lại vào ngày mai", "ai.budget-exceeded"),
    BLOCKED("AI_422_BLOCKED", 422, "AI không thể tạo gợi ý cho nội dung này. Vui lòng tự nhập thông tin", "ai.blocked"),
    BUSY("AI_503_BUSY", 503, "Dịch vụ AI đang quá tải. Vui lòng thử lại sau ít phút", "ai.busy"),
    PROVIDER_ERROR("AI_502_PROVIDER", 502, "Dịch vụ AI tạm thời không phản hồi đúng. Vui lòng thử lại sau", "ai.provider-error"),
    SUGGESTION_NOT_FOUND("AI_404_SUGGESTION", 404, "Không tìm thấy gợi ý", "ai.suggestion-not-found"),
    SUGGESTION_REVIEWED("AI_409_REVIEWED", 409, "Gợi ý này đã được xử lý", "ai.suggestion-reviewed");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
