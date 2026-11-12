package com.company.yoga.content;

import com.company.yoga.common.api.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ContentResultCodes implements ErrorCode {

    VIDEO_NOT_FOUND("VIDEO_404", 404, "Không tìm thấy video", "video.not-found"),
    COLLECTION_NOT_FOUND("VIDEO_404_COLLECTION", 404, "Không tìm thấy lộ trình", "video.collection-not-found"),
    TAG_CODE_EXISTS("VIDEO_409_TAG", 409, "Mã chủ đề đã tồn tại", "video.tag-code-exists"),
    VIDEO_NOT_EDITABLE("VIDEO_409_EDIT", 409, "Video đang xuất bản hoặc không thuộc quyền của bạn nên không sửa được", "video.not-editable"),
    VIDEO_NOT_READY("VIDEO_409_NOT_READY", 409, "Video chưa chuyển mã xong nên chưa xuất bản được", "video.not-ready"),
    VIDEO_INVALID_STATUS("VIDEO_409_STATUS", 409, "Trạng thái video không cho phép thao tác này", "video.invalid-status"),
    VIDEO_IN_USE("VIDEO_409_IN_USE", 409, "Video đã có người xem hoặc nằm trong lộ trình; hãy ẩn (lưu trữ) thay vì xóa", "video.in-use"),
    COLLECTION_INVALID_VIDEO("VIDEO_422_COLLECTION", 422, "Lộ trình chỉ gồm các video đã chuyển mã xong, mỗi video một lần", "video.collection-invalid-video"),
    VIDEO_LOCKED("VIDEO_403_LOCKED", 403, "Video dành cho học viên có gói Online hoặc Kết hợp còn hạn", "video.locked"),
    WAIVER_REQUIRED("VIDEO_428_WAIVER", 428, "Vui lòng đọc và xác nhận cam kết an toàn trước khi xem video lần đầu", "video.waiver-required"),
    DEVICE_LIMIT("VIDEO_409_DEVICES", 409, "Tài khoản đang xem video trên số thiết bị tối đa. Hãy dừng xem ở thiết bị khác rồi thử lại", "video.device-limit"),
    SESSION_EXPIRED("VIDEO_409_SESSION", 409, "Phiên xem đã hết hạn. Vui lòng mở lại video", "video.session-expired"),
    NOT_CONFIGURED("VIDEO_503_CONFIG", 503, "Thư viện video chưa được cấu hình. Vui lòng liên hệ quản trị", "video.not-configured"),
    PROVIDER_ERROR("VIDEO_502_PROVIDER", 502, "Máy chủ video tạm thời không phản hồi. Vui lòng thử lại sau", "video.provider-error");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
