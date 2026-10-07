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
import com.company.yoga.schedule.booking.dto.WaitlistDto;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.entity.ClassTypeEntity;
import com.company.yoga.schedule.booking.entity.WaitlistEntity;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import com.company.yoga.schedule.booking.repository.WaitlistRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.company.yoga.identity.account.service.AccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistServiceImpl implements WaitlistService {

    private final AccessPolicy accessPolicy;
    private final WaitlistRepository waitlistRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final MembershipRepository membershipRepository;
    private final BookingRepository bookingRepository;
    private final ClassTypeRepository classTypeRepository;
    private final RoomRepository roomRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public WaitlistDto.Resp joinWaitlist(WaitlistDto.JoinReq req) {
        UUID scheduleId = req.scheduleId();
        UUID studentId = req.studentId();
        UUID membershipId = req.membershipId();

        // 1. Khóa ca học để kiểm tra trạng thái
        ClassScheduleEntity schedule = scheduleRepository.findByIdWithLock(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));

        accessPolicy.requireBooking(studentId, schedule.getBranchId());
        if (schedule.getInstructorId() != null && userRepository.findById(schedule.getInstructorId()).filter(u -> Boolean.TRUE.equals(u.getIsActive())).isEmpty())
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT, "Ca học cần phân công huấn luyện viên đang hoạt động.");
        if (Instant.now().isAfter(schedule.getStartTime())) {
            throw new BusinessException(ScheduleResultCodes.CANCELLATION_WINDOW_CLOSED);
        }

        // 2. Kiểm tra nếu lớp chưa đầy thì khuyến khích đặt chỗ trực tiếp
        int bookedCount = schedule.getBookedCount() != null ? schedule.getBookedCount() : 0;
        if (bookedCount < schedule.getMaxCapacity()) {
            throw new BusinessException(ScheduleResultCodes.CLASS_NOT_FULL);
        }

        // 3. Kiểm tra xem học viên đã có booking CONFIRMED hoặc đang trong hàng chờ chưa
        if (bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED").isPresent()) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_ALREADY_EXISTS);
        }

        if (bookingRepository.existsStudentTimeConflict(studentId, scheduleId, schedule.getStartTime(), schedule.getEndTime())) {
            throw new BusinessException(ScheduleResultCodes.STUDENT_SCHEDULE_CONFLICT);
        }

        if (waitlistRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "WAITING").isPresent()) {
            throw new BusinessException(ScheduleResultCodes.WAITLIST_ALREADY_JOINED);
        }

        // 4. Kiểm tra điều kiện hợp lệ của thẻ tập
        MembershipEntity membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE));

        if (!Objects.equals(membership.getStudentId(), studentId) || !"ACTIVE".equalsIgnoreCase(membership.getStatus())) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE);
        }

        // Kiểm tra chi nhánh áp dụng: Thẻ đơn cơ sở chỉ được phép vào hàng chờ tại đúng chi nhánh đã đăng ký
        boolean isAllBranches = Boolean.TRUE.equals(membership.getIsAllBranches());
        if (!isAllBranches) {
            if (schedule.getBranchId() == null || membership.getRegisteredBranchId() == null
                    || !Objects.equals(schedule.getBranchId(), membership.getRegisteredBranchId())) {
                log.warn("Chặn hàng chờ: Học viên {} dùng thẻ đơn cơ sở {} (thuộc cơ sở {}) để vào hàng chờ ca học {} (thuộc cơ sở {})",
                        studentId, membership.getId(), membership.getRegisteredBranchId(), schedule.getId(), schedule.getBranchId());
                throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_BRANCH_MISMATCH);
            }
        }

        if (membership.getRemainingSessions() != null && membership.getRemainingSessions() <= 0) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NO_SESSIONS);
        }

        LocalDate scheduleDate = schedule.getStartTime().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        if (membership.getStartDate() != null && scheduleDate.isBefore(membership.getStartDate())) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE);
        }
        if (membership.getEndDate() != null && scheduleDate.isAfter(membership.getEndDate())) {
            throw new BusinessException(ScheduleResultCodes.MEMBERSHIP_NOT_ACTIVE);
        }

        // 5. Xác định vị trí tiếp theo trong hàng chờ
        int nextPosition = waitlistRepository.findMaxQueuePositionByScheduleId(scheduleId) + 1;

        WaitlistEntity entity = new WaitlistEntity();
        entity.setScheduleId(scheduleId);
        entity.setStudentId(studentId);
        entity.setMembershipId(membershipId);
        entity.setQueuePosition(nextPosition);
        entity.setStatus("WAITING");

        WaitlistEntity saved = waitlistRepository.save(entity);
        log.info("Student {} joined waitlist for schedule {} at position #{}", studentId, scheduleId, nextPosition);

        return new WaitlistDto.Resp(
                saved.getId(),
                saved.getScheduleId(),
                saved.getStudentId(),
                saved.getMembershipId(),
                saved.getQueuePosition(),
                saved.getStatus(),
                saved.getConvertedBookingId(),
                saved.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public WaitlistDto.Resp cancelWaitlist(UUID waitlistId, UUID studentId) {
        WaitlistEntity waitlist = waitlistRepository.findById(waitlistId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.WAITLIST_NOT_FOUND));

        var targetSchedule = scheduleRepository.findByIdWithLock(waitlist.getScheduleId()).orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        accessPolicy.requireBooking(waitlist.getStudentId(), targetSchedule.getBranchId());
        if (!Objects.equals(waitlist.getStudentId(), studentId)) {
            throw new BusinessException(ScheduleResultCodes.WAITLIST_NOT_FOUND);
        }

        if (!"WAITING".equalsIgnoreCase(waitlist.getStatus())) {
            throw new BusinessException(ScheduleResultCodes.BOOKING_NOT_CONFIRMED);
        }

        waitlist.setStatus("CANCELLED");
        WaitlistEntity saved = waitlistRepository.save(waitlist);

        return new WaitlistDto.Resp(
                saved.getId(),
                saved.getScheduleId(),
                saved.getStudentId(),
                saved.getMembershipId(),
                saved.getQueuePosition(),
                saved.getStatus(),
                saved.getConvertedBookingId(),
                saved.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public Optional<BookingEntity> autoPromoteTopCandidate(UUID scheduleId) {
        ClassScheduleEntity schedule = scheduleRepository.findByIdWithLock(scheduleId).orElse(null);
        if (schedule == null || !"SCHEDULED".equals(schedule.getStatus()) || !Instant.now().isBefore(schedule.getStartTime())) {
            return Optional.empty();
        }

        // Native scalar reads the trigger-updated value, bypassing the entity cache.
        int bookedCount = scheduleRepository.currentBookedCount(scheduleId);
        if (bookedCount >= schedule.getMaxCapacity()) {
            return Optional.empty();
        }

        List<WaitlistEntity> candidates = waitlistRepository
                .findByScheduleIdAndStatusOrderByQueuePositionAsc(scheduleId, "WAITING");

        for (WaitlistEntity candidate : candidates) {
            Optional<MembershipEntity> mbOpt = membershipRepository.findById(candidate.getMembershipId());
            if (mbOpt.isEmpty()) {
                candidate.setStatus("EXPIRED");
                waitlistRepository.save(candidate);
                continue;
            }

            MembershipEntity mb = mbOpt.get();
            LocalDate scheduleDate = schedule.getStartTime().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
            boolean isValid = "ACTIVE".equalsIgnoreCase(mb.getStatus())
                    && (mb.getRemainingSessions() == null || mb.getRemainingSessions() > 0)
                    && (mb.getStartDate() == null || !scheduleDate.isBefore(mb.getStartDate()))
                    && (mb.getEndDate() == null || !scheduleDate.isAfter(mb.getEndDate()))
                    && (Boolean.TRUE.equals(mb.getIsAllBranches()) || Objects.equals(mb.getRegisteredBranchId(), schedule.getBranchId()))
                    && Objects.equals(mb.getStudentId(), candidate.getStudentId())
                    && userRepository.findById(candidate.getStudentId()).map(u -> Boolean.TRUE.equals(u.getIsActive())).orElse(false)
                    && bookingRepository.activeBookingCount(scheduleId, candidate.getStudentId()) == 0
                    && !bookingRepository.existsStudentTimeConflict(candidate.getStudentId(), scheduleId, schedule.getStartTime(), schedule.getEndTime())
                    && bookingRepository.freezeCount(mb.getId(), scheduleDate) == 0;

            if (!isValid) {
                candidate.setStatus("EXPIRED");
                waitlistRepository.save(candidate);
                continue;
            }

            // Tạo booking CONFIRMED
            String bookingCode = "BK-" + java.util.UUID.randomUUID();
            BookingEntity newBooking = new BookingEntity();
            newBooking.setBookingCode(bookingCode);
            newBooking.setScheduleId(scheduleId);
            newBooking.setStudentId(candidate.getStudentId());
            newBooking.setMembershipId(candidate.getMembershipId());
            var occupied = bookingRepository.occupiedMats(scheduleId);
            newBooking.setMatNumber(java.util.stream.IntStream.rangeClosed(1, schedule.getMaxCapacity()).filter(n -> !occupied.contains(n)).boxed().findFirst().orElse(null));
            newBooking.setStatus("CONFIRMED");
            newBooking.setBookingTime(Instant.now());
            BookingEntity savedBooking = bookingRepository.saveAndFlush(newBooking);

            // Cập nhật waitlist sang CONVERTED
            candidate.setStatus("CONVERTED");
            candidate.setConvertedBookingId(savedBooking.getId());
            waitlistRepository.saveAndFlush(candidate);

            log.info("Auto-promoted student {} from waitlist #{} to confirmed booking {} for schedule {}",
                    candidate.getStudentId(), candidate.getQueuePosition(), savedBooking.getBookingCode(), scheduleId);

            return Optional.of(savedBooking);
        }

        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WaitlistDto.StudentWaitlistResp> getStudentWaitlists(UUID studentId) {
        accessPolicy.requireStudent(studentId);
        return waitlistRepository.findStudentDetails(studentId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getWaitingCount(UUID scheduleId) {
        return waitlistRepository.countByScheduleIdAndStatus(scheduleId, "WAITING");
    }
}
