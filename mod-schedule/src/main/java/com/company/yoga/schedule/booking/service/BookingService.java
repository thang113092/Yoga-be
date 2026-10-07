package com.company.yoga.schedule.booking.service;

import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.entity.RoomEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.membership.plan.entity.MembershipEntity;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.entity.ClassTypeEntity;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.company.yoga.identity.account.service.AccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final AccessPolicy accessPolicy;
    private final ClassScheduleRepository scheduleRepository;
    private final BookingRepository bookingRepository;
    private final MembershipRepository membershipRepository;
    private final ClassTypeRepository classTypeRepository;
    private final RoomRepository roomRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final WaitlistService waitlistService;

    @Transactional
    public BookingDto.Resp createBooking(BookingDto.CreateReq req) {
        UUID scheduleId = req.scheduleId();
        UUID studentId = req.studentId();
        UUID membershipId = req.membershipId();

        // 1. Khóa bi quan ca học để chống overbooking cạnh tranh đồng thời
        ClassScheduleEntity schedule = scheduleRepository.findByIdWithLock(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));

        accessPolicy.requireBooking(studentId, schedule.getBranchId());
        if (schedule.getInstructorId() != null && userRepository.findById(schedule.getInstructorId()).filter(u -> Boolean.TRUE.equals(u.getIsActive())).isEmpty())
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Ca học cần phân công huấn luyện viên đang hoạt động.");
        if (!"SCHEDULED".equals(schedule.getStatus()) || !Instant.now().isBefore(schedule.getStartTime())) throw new BusinessException(ScheduleResultCodes.CANCELLATION_WINDOW_CLOSED);
        int currentBooked = schedule.getBookedCount() != null ? schedule.getBookedCount() : 0;
        if (currentBooked >= schedule.getMaxCapacity()) {
            throw new BusinessException(ScheduleResultCodes.CLASS_FULL);
        }

        // 2. Chặn đặt lặp ca học đang active hoặc trùng khung giờ với ca học khác đã đặt
        if (bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED").isPresent()) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_ALREADY_EXISTS);
        }
        if (bookingRepository.existsStudentTimeConflict(studentId, scheduleId, schedule.getStartTime(), schedule.getEndTime())) {
            throw new BusinessException(ScheduleResultCodes.STUDENT_SCHEDULE_CONFLICT);
        }

        // 3. Kiểm tra tính hợp lệ của Thẻ tập (Membership Contract)
        MembershipEntity membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE));

        if (!Objects.equals(membership.getStudentId(), studentId) || !"ACTIVE".equalsIgnoreCase(membership.getStatus())) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE);
        }

        // Kiểm tra chi nhánh áp dụng: Thẻ đơn cơ sở chỉ được phép đặt tại đúng chi nhánh đã đăng ký
        boolean isAllBranches = Boolean.TRUE.equals(membership.getIsAllBranches());
        if (!isAllBranches) {
            if (schedule.getBranchId() == null || membership.getRegisteredBranchId() == null
                    || !Objects.equals(schedule.getBranchId(), membership.getRegisteredBranchId())) {
                log.warn("Chặn đặt chỗ: Học viên {} dùng thẻ đơn cơ sở {} (thuộc cơ sở {}) để đặt ca học {} (thuộc cơ sở {})",
                        studentId, membership.getId(), membership.getRegisteredBranchId(), schedule.getId(), schedule.getBranchId());
                throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_BRANCH_MISMATCH);
            }
        }

        // Kiểm tra số buổi còn lại
        if (membership.getRemainingSessions() != null && membership.getRemainingSessions() <= 0) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NO_SESSIONS);
        }

        // Kiểm tra thời hạn hiệu lực thẻ
        LocalDate scheduleDate = schedule.getStartTime().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        if (membership.getStartDate() != null && scheduleDate.isBefore(membership.getStartDate())) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE);
        }
        if (membership.getEndDate() != null && scheduleDate.isAfter(membership.getEndDate())) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE);
        }

        // 4. Lưu bản ghi Booking (Trigger CSDL maintain_booking_balances sẽ tự trừ remaining_sessions & ghi ledger)
        String bookingCode = "BK-" + java.util.UUID.randomUUID();
        BookingEntity booking = new BookingEntity();
        booking.setBookingCode(bookingCode);
        booking.setScheduleId(scheduleId);
        booking.setStudentId(studentId);
        booking.setMembershipId(membershipId);
        var occupied = bookingRepository.occupiedMats(scheduleId);
        Integer mat = req.matNumber();
        if (mat == null) mat = java.util.stream.IntStream.rangeClosed(1, schedule.getMaxCapacity()).filter(n -> !occupied.contains(n)).boxed().findFirst().orElse(null);
        if (mat == null || mat > schedule.getMaxCapacity() || mat <= 0 || occupied.contains(mat)) throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Mat is unavailable");
        booking.setMatNumber(mat);
        booking.setStatus("CONFIRMED");
        booking.setBookingTime(Instant.now());

        BookingEntity saved = bookingRepository.save(booking);

        return new BookingDto.Resp(
                saved.getId(),
                saved.getBookingCode(),
                saved.getScheduleId(),
                saved.getStudentId(),
                saved.getMembershipId(),
                saved.getMatNumber(),
                saved.getStatus(),
                saved.getBookingTime()
        );
    }

    @Transactional
    public BookingDto.Resp cancelBooking(UUID bookingId, String reason) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.BOOKING_NOT_FOUND));

        if (!"CONFIRMED".equalsIgnoreCase(booking.getStatus())) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_NOT_CONFIRMED);
        }

        ClassScheduleEntity schedule = scheduleRepository.findByIdWithLock(booking.getScheduleId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));

        accessPolicy.requireBooking(booking.getStudentId(), schedule.getBranchId());
        // Không cho phép hủy sau khi ca học đã bắt đầu
        if (!Instant.now().isBefore(schedule.getStartTime())) {
            throw new BusinessException(ScheduleResultCodes.CANCELLATION_WINDOW_CLOSED);
        }

        booking.setStatus("CANCELLED");
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(reason != null ? reason : "Học viên hủy ca học");

        BookingEntity saved = bookingRepository.saveAndFlush(booking);

        // Sau khi hủy chỗ thành công, tự động kiểm tra và đôn học viên đầu tiên trong hàng chờ (Waitlist)
        // Cancellation and promotion are atomic: an error rolls back both.
        waitlistService.autoPromoteTopCandidate(schedule.getId());

        return new BookingDto.Resp(
                saved.getId(),
                saved.getBookingCode(),
                saved.getScheduleId(),
                saved.getStudentId(),
                saved.getMembershipId(),
                saved.getMatNumber(),
                saved.getStatus(),
                saved.getBookingTime()
        );
    }

    @Transactional(readOnly = true)
    public List<BookingDto.Resp> getStudentBookings(UUID studentId) {
        accessPolicy.requireStudent(studentId);
        return bookingRepository.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .map(b -> new BookingDto.Resp(
                        b.getId(),
                        b.getBookingCode(),
                        b.getScheduleId(),
                        b.getStudentId(),
                        b.getMembershipId(),
                        b.getMatNumber(),
                        b.getStatus(),
                        b.getBookingTime()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto.StudentBookingDetailResp> getStudentBookingDetails(UUID studentId) {
        accessPolicy.requireStudent(studentId);
        return bookingRepository.findStudentDetails(studentId, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<BookingDto.WorkoutHistoryResp> getStudentWorkoutHistory(UUID studentId) {
        accessPolicy.requireStudent(studentId);
        return bookingRepository.findStudentWorkoutHistory(studentId);
    }
}
