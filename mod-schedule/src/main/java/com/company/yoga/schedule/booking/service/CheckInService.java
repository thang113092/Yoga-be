package com.company.yoga.schedule.booking.service;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.dto.CheckInDto;
import com.company.yoga.schedule.booking.entity.AttendanceRecordEntity;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.repository.AttendanceRecordRepository;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.company.yoga.identity.account.service.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckInService {

    private final AccessPolicy accessPolicy;
    private final BookingRepository bookingRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final ClassTypeRepository classTypeRepository;

    @Transactional
    public CheckInDto.CheckInResp processCheckIn(CheckInDto.CheckInReq req) {
        BookingEntity booking = null;
        if (req.bookingId() != null) {
            booking = bookingRepository.findById(req.bookingId()).orElse(null);
        }
        if (booking == null && req.bookingCode() != null && !req.bookingCode().isBlank()) {
            booking = bookingRepository.findByBookingCode(req.bookingCode().trim()).orElse(null);
        }
        if (booking == null) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_NOT_FOUND);
        }

        ClassScheduleEntity schedule = scheduleRepository.findByIdWithLock(booking.getScheduleId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));

        if (req.branchId() != null && !req.branchId().equals(schedule.getBranchId()))
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST, "Check-in branch must match schedule");
        accessPolicy.requireInstructorSchedule(schedule.getBranchId(), schedule.getInstructorId());
        String studentName = userRepository.findById(booking.getStudentId())
                .map(u -> u.getFullName())
                .orElse("Học viên Yoga");

        String className = classTypeRepository.findById(schedule.getClassTypeId())
                .map(ct -> ct.getName())
                .orElse("Lớp Yoga");

        String branchName = branchRepository.findById(schedule.getBranchId())
                .map(b -> b.getName())
                .orElse("Chi nhánh phòng tập");

        // 1. Idempotency Guard: Nếu đã điểm danh trước đó, trả về thông tin thành công mà không trừ tiếp
        Optional<AttendanceRecordEntity> existing = attendanceRecordRepository.findByBookingId(booking.getId());
        if (existing.isPresent()) {
            AttendanceRecordEntity att = existing.get();
            return new CheckInDto.CheckInResp(
                    att.getId(),
                    booking.getId(),
                    booking.getBookingCode(),
                    booking.getStudentId(),
                    studentName,
                    className,
                    branchName,
                    att.getCheckedInAt(),
                    att.getAttendanceStatus(),
                    booking.getMatNumber(),
                    true
            );
        }

        // 2. Kiểm tra trạng thái đặt chỗ
        if (!"CONFIRMED".equalsIgnoreCase(booking.getStatus())) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_NOT_CONFIRMED);
        }

        // 3. Kiểm tra cửa sổ thời gian điểm danh (HLV có cửa sổ linh hoạt để điểm danh trước/trong/sau ca)
        Instant now = Instant.now();
        boolean isInstructorAction = "INSTRUCTOR_CONFIRM".equalsIgnoreCase(req.checkInMethod())
                || "INSTRUCTOR".equalsIgnoreCase(accessPolicy.role(accessPolicy.actor()));
        Instant openWindow = isInstructorAction
                ? schedule.getStartTime().minus(Duration.ofHours(2))
                : schedule.getStartTime().minus(Duration.ofMinutes(30));
        Instant closeWindow = isInstructorAction
                ? schedule.getEndTime().plus(Duration.ofHours(12))
                : schedule.getEndTime();

        if (now.isBefore(openWindow)) {
            throw new BusinessException(ScheduleResultCodes.CHECKIN_WINDOW_NOT_OPEN);
        }
        if (!now.isBefore(closeWindow)) {
            throw new BusinessException(ScheduleResultCodes.CHECKIN_WINDOW_EXPIRED);
        }

        // 4. Lưu bản ghi điểm danh Append-Only
        AttendanceRecordEntity record = new AttendanceRecordEntity();
        record.setScheduleId(schedule.getId());
        record.setStudentId(booking.getStudentId());
        record.setMembershipId(booking.getMembershipId());
        record.setBookingId(booking.getId());
        record.setCheckedInBranchId(req.branchId() != null ? req.branchId() : schedule.getBranchId());
        record.setCheckedInAt(now);
        record.setCheckedInBy(accessPolicy.actor().getId());
        record.setCheckInMethod(req.checkInMethod() != null ? req.checkInMethod() : "QR_SCAN");
        record.setAttendanceStatus("PRESENT");
        record.setNotes(req.notes());

        AttendanceRecordEntity saved = attendanceRecordRepository.saveAndFlush(record);

        // 5. Cập nhật trạng thái booking sang ATTENDED
        booking.setStatus("ATTENDED");
        bookingRepository.save(booking);

        return new CheckInDto.CheckInResp(
                saved.getId(),
                booking.getId(),
                booking.getBookingCode(),
                booking.getStudentId(),
                studentName,
                className,
                branchName,
                saved.getCheckedInAt(),
                saved.getAttendanceStatus(),
                booking.getMatNumber(),
                false
        );
    }

    @Transactional
    public void revertCheckIn(UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.BOOKING_NOT_FOUND));
        ClassScheduleEntity schedule = scheduleRepository.findByIdWithLock(booking.getScheduleId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        accessPolicy.requireInstructorSchedule(schedule.getBranchId(), schedule.getInstructorId());

        attendanceRecordRepository.findByBookingId(bookingId).ifPresent(attendanceRecordRepository::delete);
        booking.setStatus("CONFIRMED");
        bookingRepository.save(booking);
    }
}
