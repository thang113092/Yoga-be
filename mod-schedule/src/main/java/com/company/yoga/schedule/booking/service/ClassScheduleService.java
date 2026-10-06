package com.company.yoga.schedule.booking.service;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserBranchEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.entity.RoleEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.company.yoga.identity.account.service.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClassScheduleService {

    private final AccessPolicy accessPolicy;
    private final ClassScheduleRepository scheduleRepository;
    private final ClassTypeRepository classTypeRepository;
    private final RoomRepository roomRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserBranchRepository userBranchRepository;
    private final com.company.yoga.schedule.booking.repository.BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public List<ScheduleDto.Resp> getSchedulesByBranch(UUID branchId, Instant start, Instant end) {
        if (start != null && end != null && start.isAfter(end)) throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST);
        Instant lower = start != null ? start : Instant.now();
        Instant upper = end != null ? end : lower.plus(java.time.Duration.ofDays(31));
        if (upper.isBefore(lower) || java.time.Duration.between(lower, upper).toDays() > 93) throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST);
        return scheduleRepository.findSummaries(branchId, lower, upper);
    }

    @Transactional(readOnly = true)
    public ScheduleDto.Resp getScheduleById(UUID scheduleId) {
        ClassScheduleEntity entity = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        return toResp(entity);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public ScheduleDto.Resp createSchedule(ScheduleDto.CreateReq req) {
        accessPolicy.requireStaffBranch(req.branchId());
        if (req.maxCapacity() <= 0 || !req.endTime().isAfter(req.startTime()) || !req.startTime().isAfter(Instant.now()))
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.BAD_REQUEST);

        // 1. Kiểm tra phòng tập
        var room = roomRepository.findById(req.roomId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.ROOM_INACTIVE));
        if (!req.branchId().equals(room.getBranchId()) || !Boolean.TRUE.equals(room.getIsActive())) {
            throw new BusinessException(ScheduleResultCodes.ROOM_INACTIVE);
        }
        if (req.maxCapacity() > room.getMaxCapacity()) {
            throw new BusinessException(ScheduleResultCodes.ROOM_CAPACITY_EXCEEDED);
        }

        // 2. Kiểm tra huấn luyện viên
        UserEntity instructor = userRepository.findById(req.instructorId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.INSTRUCTOR_NOT_ELIGIBLE));
        if (!Boolean.TRUE.equals(instructor.getIsActive())) {
            throw new BusinessException(ScheduleResultCodes.INSTRUCTOR_NOT_ELIGIBLE);
        }
        RoleEntity role = roleRepository.findById(instructor.getRoleId())
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.INSTRUCTOR_NOT_ELIGIBLE));
        if (!"INSTRUCTOR".equalsIgnoreCase(role.getCode())) {
            throw new BusinessException(ScheduleResultCodes.INSTRUCTOR_NOT_ELIGIBLE);
        }

        // Đảm bảo HLV được phân công vào chi nhánh này trong user_branches (đáp ứng trigger validate_schedule)
        boolean hasBranchAssignment = userBranchRepository.existsByUserIdAndBranchId(instructor.getId(), req.branchId());
        if (!hasBranchAssignment) {
            UserBranchEntity assignment = new UserBranchEntity();
            assignment.setId(UUID.randomUUID());
            assignment.setUserId(instructor.getId());
            assignment.setBranchId(req.branchId());
            assignment.setIsPrimary(req.branchId().equals(instructor.getHomeBranchId()));
            userBranchRepository.saveAndFlush(assignment);
        }

        ClassScheduleEntity entity = new ClassScheduleEntity();
        entity.setBranchId(req.branchId());
        entity.setRoomId(req.roomId());
        entity.setClassTypeId(req.classTypeId());
        entity.setInstructorId(req.instructorId());
        entity.setStartTime(req.startTime());
        entity.setEndTime(req.endTime());
        entity.setMaxCapacity(req.maxCapacity());
        entity.setStatus("SCHEDULED");

        ClassScheduleEntity saved = scheduleRepository.save(entity);
        return toResp(saved);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public ScheduleDto.Resp cancelSchedule(UUID scheduleId) {
        ClassScheduleEntity entity = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        accessPolicy.requireStaffBranch(entity.getBranchId());

        entity.setStatus("CANCELLED");
        ClassScheduleEntity saved = scheduleRepository.save(entity);
        return toResp(saved);
    }

    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER', 'RECEPTIONIST', 'INSTRUCTOR')")
    public List<com.company.yoga.schedule.booking.dto.BookingDto.AttendeeResp> getScheduleAttendees(UUID scheduleId) {
        ClassScheduleEntity entity = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleResultCodes.SCHEDULE_NOT_FOUND));
        accessPolicy.requireStaffBranch(entity.getBranchId());

        return bookingRepository.findScheduleAttendees(scheduleId);
    }

    private ScheduleDto.Resp toResp(ClassScheduleEntity s) {
        int booked = s.getBookedCount() != null ? s.getBookedCount() : 0;
        int available = Math.max(0, s.getMaxCapacity() - booked);

        String className = classTypeRepository.findById(s.getClassTypeId())
                .map(ct -> ct.getName())
                .orElse("Lớp Yoga Căn Bản");

        String instructorName = userRepository.findById(s.getInstructorId())
                .map(u -> u.getFullName())
                .orElse("Huấn luyện viên");

        String roomName = roomRepository.findById(s.getRoomId())
                .map(r -> r.getName() + (r.getFloor() != null ? " (" + r.getFloor() + ")" : ""))
                .orElse("Phòng tập");

        String branchName = branchRepository.findById(s.getBranchId())
                .map(b -> b.getName())
                .orElse("Chi nhánh");

        return new ScheduleDto.Resp(
                s.getId(),
                s.getBranchId(),
                branchName,
                s.getRoomId(),
                s.getInstructorId(),
                className,
                instructorName,
                roomName,
                s.getStartTime(),
                s.getEndTime(),
                s.getMaxCapacity(),
                booked,
                available,
                s.getStatus()
        );
    }
}
