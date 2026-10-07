package com.company.yoga.schedule;

import com.company.yoga.common.api.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ScheduleResultCodes implements ErrorCode {

    SCHEDULE_NOT_FOUND("SCHED_404", 404, "Không tìm thấy ca học", "schedule.not-found"),
    CLASS_FULL("SCHED_409", 409, "Ca học đã kín chỗ, vui lòng vào hàng chờ", "schedule.full"),
    BOOKING_ALREADY_EXISTS("BOOKING_409", 409, "Bạn đã đặt chỗ cho ca học này rồi", "booking.already-exists"),
    BOOKING_NOT_FOUND("BOOKING_404", 404, "Không tìm thấy lượt đặt chỗ", "booking.not-found"),
    CANCELLATION_WINDOW_CLOSED("BOOKING_422", 422, "Đã hết thời hạn hủy lớp (lớp đã hoặc sắp diễn ra)", "booking.cancel-closed"),
    CHECKIN_WINDOW_NOT_OPEN("CHECKIN_422", 422, "Chưa đến thời gian điểm danh (chỉ mở trước giờ học 30 phút)", "checkin.window-not-open"),
    CHECKIN_WINDOW_EXPIRED("CHECKIN_422", 422, "Ca học đã kết thúc, không thể điểm danh", "checkin.window-expired"),
    BOOKING_NOT_CONFIRMED("CHECKIN_422", 422, "Trạng thái đặt chỗ không hợp lệ để điểm danh", "checkin.booking-not-confirmed"),
    MEMBERSHIP_NOT_ACTIVE("BOOKING_422", 422, "Thẻ tập chưa được kích hoạt hoặc đã hết hạn", "booking.membership-inactive"),
    MEMBERSHIP_NO_SESSIONS("BOOKING_422", 422, "Thẻ tập đã hết số lượt tham gia", "booking.membership-no-sessions"),
    MEMBERSHIP_BRANCH_MISMATCH("BOOKING_422", 422, "Thẻ tập không được áp dụng tại cơ sở này", "booking.branch-mismatch"),
    WAITLIST_ALREADY_JOINED("WAITLIST_409", 409, "Bạn đã đăng ký trong hàng chờ của ca học này rồi", "waitlist.already-joined"),
    WAITLIST_NOT_FOUND("WAITLIST_404", 404, "Không tìm thấy thông tin hàng chờ", "waitlist.not-found"),
    CLASS_NOT_FULL("WAITLIST_400", 400, "Ca học vẫn còn chỗ trống, bạn có thể đặt chỗ trực tiếp thay vì vào hàng chờ", "waitlist.class-not-full"),
    INSTRUCTOR_NOT_ELIGIBLE("SCHED_422_INSTRUCTOR", 422, "Huấn luyện viên không hoạt động hoặc không được phân công tại chi nhánh này", "schedule.instructor-not-eligible"),
    ROOM_INACTIVE("SCHED_422_ROOM_INACTIVE", 422, "Phòng tập đang tạm ngưng hoạt động hoặc không thuộc chi nhánh này", "schedule.room-inactive"),
    ROOM_CAPACITY_EXCEEDED("SCHED_422_ROOM_CAPACITY", 422, "Sức chứa ca học vượt quá sức chứa tối đa của phòng tập", "schedule.room-capacity-exceeded"),
    ROOM_SCHEDULE_OVERLAP("SCHED_409_ROOM_OVERLAP", 409, "Phòng tập đã có ca học khác trong khung giờ đã chọn", "schedule.room-overlap"),
    INSTRUCTOR_SCHEDULE_OVERLAP("SCHED_409_INSTRUCTOR_OVERLAP", 409, "Huấn luyện viên đã có lịch dạy lớp khác trong khung giờ đã chọn", "schedule.instructor-overlap"),
    STUDENT_SCHEDULE_CONFLICT("BOOKING_409_CONFLICT", 409, "Bạn đã có một ca học khác trong khung giờ này. Vui lòng kiểm tra lại lịch học cá nhân.", "booking.student-time-conflict"),
    CLASS_TYPE_NOT_FOUND("CLASS_TYPE_404", 404, "Không tìm thấy bộ môn yoga", "class-type.not-found"),
    CLASS_TYPE_IN_USE("CLASS_TYPE_409_IN_USE", 409, "Không thể xóa bộ môn vì đã có các ca học được xếp lịch", "class-type.in-use");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
