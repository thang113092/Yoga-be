package com.company.yoga.schedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.membership.plan.entity.MembershipEntity;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private ClassScheduleRepository scheduleRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private ClassTypeRepository classTypeRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WaitlistService waitlistService;

    @Mock private com.company.yoga.identity.account.service.AccessPolicy accessPolicy;

    @InjectMocks
    private BookingService bookingService;

    private UUID scheduleId;
    private UUID studentId;
    private UUID membershipId;
    private UUID branchId;
    private ClassScheduleEntity schedule;
    private MembershipEntity membership;

    @Test
    void cannotBookScheduleOfDeletedInstructor() {
        UUID instructor = UUID.randomUUID();
        ClassScheduleEntity inactiveSchedule = new ClassScheduleEntity();
        inactiveSchedule.setId(UUID.randomUUID()); inactiveSchedule.setInstructorId(instructor);
        when(scheduleRepository.findByIdWithLock(inactiveSchedule.getId())).thenReturn(Optional.of(inactiveSchedule));
        when(userRepository.findById(instructor)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> bookingService.createBooking(new BookingDto.CreateReq(inactiveSchedule.getId(), UUID.randomUUID(), UUID.randomUUID(), null)));
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @BeforeEach
    void setUp() {
        scheduleId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        membershipId = UUID.randomUUID();
        branchId = UUID.randomUUID();

        schedule = new ClassScheduleEntity();
        schedule.setId(scheduleId);
        schedule.setBranchId(branchId);
        schedule.setMaxCapacity(20);
        schedule.setStartTime(Instant.now().plus(2, ChronoUnit.HOURS));
        schedule.setEndTime(Instant.now().plus(3, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(schedule, "bookedCount", 5);

        membership = new MembershipEntity();
        membership.setId(membershipId);
        membership.setStudentId(studentId);
        membership.setStatus("ACTIVE");
        membership.setRegisteredBranchId(branchId);
        membership.setIsAllBranches(false);
        membership.setStartDate(LocalDate.now().minusDays(5));
        membership.setEndDate(LocalDate.now().plusDays(30));
        ReflectionTestUtils.setField(membership, "remainingSessions", 10);
    }

    @Test
    @DisplayName("Đặt chỗ thành công khi còn chỗ và thẻ hợp lệ")
    void testCreateBooking_Success() {
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED"))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsStudentTimeConflict(studentId, scheduleId, schedule.getStartTime(), schedule.getEndTime()))
                .thenReturn(false);
        when(membershipRepository.findById(membershipId)).thenReturn(Optional.of(membership));
        when(bookingRepository.save(any(BookingEntity.class))).thenAnswer(invocation -> {
            BookingEntity b = invocation.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        BookingDto.CreateReq req = new BookingDto.CreateReq(scheduleId, studentId, membershipId, 7);
        BookingDto.Resp resp = bookingService.createBooking(req);

        assertNotNull(resp);
        assertNotNull(resp.bookingCode());
        assertEquals("CONFIRMED", resp.status());
        assertEquals(7, resp.matNumber());
        verify(bookingRepository).save(any(BookingEntity.class));
    }

    @Test
    @DisplayName("Báo lỗi CLASS_FULL khi lớp học đã kín chỗ")
    void testCreateBooking_ThrowsClassFull_WhenCapacityReached() {
        ReflectionTestUtils.setField(schedule, "bookedCount", 20);
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));

        BookingDto.CreateReq req = new BookingDto.CreateReq(scheduleId, studentId, membershipId, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> bookingService.createBooking(req));

        assertEquals(ScheduleResultCodes.CLASS_FULL, ex.getErrorCode());
    }

    @Test
    @DisplayName("Báo lỗi BOOKING_ALREADY_EXISTS khi học viên đã đặt ca này")
    void testCreateBooking_ThrowsAlreadyExists() {
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED"))
                .thenReturn(Optional.of(new BookingEntity()));

        BookingDto.CreateReq req = new BookingDto.CreateReq(scheduleId, studentId, membershipId, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> bookingService.createBooking(req));

        assertEquals(ScheduleResultCodes.BOOKING_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    @DisplayName("Báo lỗi STUDENT_SCHEDULE_CONFLICT khi học viên đã có ca học khác trùng khung giờ")
    void testCreateBooking_ThrowsScheduleConflict_WhenTimeOverlaps() {
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED"))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsStudentTimeConflict(studentId, scheduleId, schedule.getStartTime(), schedule.getEndTime()))
                .thenReturn(true);

        BookingDto.CreateReq req = new BookingDto.CreateReq(scheduleId, studentId, membershipId, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> bookingService.createBooking(req));

        assertEquals(ScheduleResultCodes.STUDENT_SCHEDULE_CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("Hủy đặt chỗ thành công trước khi ca học bắt đầu")
    void testCancelBooking_Success_BeforeStartTime() {
        UUID bookingId = UUID.randomUUID();
        BookingEntity booking = new BookingEntity();
        booking.setId(bookingId);
        booking.setScheduleId(scheduleId);
        booking.setStatus("CONFIRMED");

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(bookingRepository.saveAndFlush(any(BookingEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BookingDto.Resp resp = bookingService.cancelBooking(bookingId, "Bận việc đột xuất");

        assertNotNull(resp);
        assertEquals("CANCELLED", resp.status());
    }

    @Test
    @DisplayName("Báo lỗi CANCELLATION_WINDOW_CLOSED khi hủy sau khi ca học đã diễn ra")
    void testCancelBooking_ThrowsWindowClosed_AfterStartTime() {
        UUID bookingId = UUID.randomUUID();
        BookingEntity booking = new BookingEntity();
        booking.setId(bookingId);
        booking.setScheduleId(scheduleId);
        booking.setStatus("CONFIRMED");

        schedule.setStartTime(Instant.now().minus(10, ChronoUnit.MINUTES));

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));

        BusinessException ex = assertThrows(BusinessException.class, () -> bookingService.cancelBooking(bookingId, null));
        assertEquals(ScheduleResultCodes.CANCELLATION_WINDOW_CLOSED, ex.getErrorCode());
    }

    @Test
    @DisplayName("Báo lỗi MEMBERSHIP_BRANCH_MISMATCH khi dùng thẻ đơn cơ sở đặt lớp ở cơ sở khác")
    void testCreateBooking_ThrowsBranchMismatch_WhenSingleBranchDifferent() {
        UUID otherBranchId = UUID.randomUUID();
        schedule.setBranchId(otherBranchId);

        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED"))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsStudentTimeConflict(studentId, scheduleId, schedule.getStartTime(), schedule.getEndTime()))
                .thenReturn(false);
        when(membershipRepository.findById(membershipId)).thenReturn(Optional.of(membership));

        BookingDto.CreateReq req = new BookingDto.CreateReq(scheduleId, studentId, membershipId, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> bookingService.createBooking(req));

        assertEquals(ScheduleResultCodes.MEMBERSHIP_BRANCH_MISMATCH, ex.getErrorCode());
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).save(any(BookingEntity.class));
    }

    @Test
    @DisplayName("Cho phép đặt chỗ ở bất kỳ cơ sở nào khi dùng thẻ toàn chuỗi (isAllBranches = true)")
    void testCreateBooking_Success_WhenAllBranchesMembership() {
        UUID otherBranchId = UUID.randomUUID();
        schedule.setBranchId(otherBranchId);
        membership.setIsAllBranches(true);

        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(bookingRepository.findByScheduleIdAndStudentIdAndStatus(scheduleId, studentId, "CONFIRMED"))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsStudentTimeConflict(studentId, scheduleId, schedule.getStartTime(), schedule.getEndTime()))
                .thenReturn(false);
        when(membershipRepository.findById(membershipId)).thenReturn(Optional.of(membership));
        when(bookingRepository.save(any(BookingEntity.class))).thenAnswer(invocation -> {
            BookingEntity b = invocation.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        BookingDto.CreateReq req = new BookingDto.CreateReq(scheduleId, studentId, membershipId, 1);
        BookingDto.Resp resp = bookingService.createBooking(req);

        assertNotNull(resp);
        assertEquals("CONFIRMED", resp.status());
        verify(bookingRepository).save(any(BookingEntity.class));
    }

    @Test
    @DisplayName("Lấy lịch sử tập luyện của học viên thành công")
    void testGetStudentWorkoutHistory() {
        UUID studentId = UUID.randomUUID();
        BookingDto.WorkoutHistoryResp item = new BookingDto.WorkoutHistoryResp(
                UUID.randomUUID(),
                "BK-TEST",
                UUID.randomUUID(),
                "Hatha Yoga",
                "BEGINNER",
                "HLV Mai",
                "Phòng Sen",
                "Cơ sở Q1",
                UUID.randomUUID(),
                Instant.now().minus(java.time.Duration.ofDays(1)),
                Instant.now().minus(java.time.Duration.ofDays(1)).plus(java.time.Duration.ofMinutes(60)),
                60,
                5,
                "ATTENDED",
                Instant.now().minus(java.time.Duration.ofDays(1)),
                "QR_SCAN",
                "PRESENT",
                "MB-001",
                "Check-in đúng giờ"
        );
        when(bookingRepository.findStudentWorkoutHistory(studentId)).thenReturn(java.util.List.of(item));

        java.util.List<BookingDto.WorkoutHistoryResp> result = bookingService.getStudentWorkoutHistory(studentId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Hatha Yoga", result.get(0).className());
        verify(accessPolicy).requireStudent(studentId);
        verify(bookingRepository).findStudentWorkoutHistory(studentId);
    }
}
