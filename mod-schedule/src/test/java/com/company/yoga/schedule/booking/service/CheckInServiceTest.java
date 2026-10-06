package com.company.yoga.schedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.dto.CheckInDto;
import com.company.yoga.schedule.booking.entity.AttendanceRecordEntity;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.repository.AttendanceRecordRepository;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class CheckInServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ClassScheduleRepository scheduleRepository;

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @Mock
    private com.company.yoga.identity.account.repository.UserRepository userRepository;

    @Mock
    private com.company.yoga.branch.facility.repository.BranchRepository branchRepository;

    @Mock
    private com.company.yoga.schedule.booking.repository.ClassTypeRepository classTypeRepository;

    @Mock private com.company.yoga.identity.account.service.AccessPolicy accessPolicy;

    @InjectMocks
    private CheckInService checkInService;

    private UUID bookingId;
    private UUID scheduleId;
    private UUID studentId;
    private UUID branchId;
    private BookingEntity booking;
    private ClassScheduleEntity schedule;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        scheduleId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        branchId = UUID.randomUUID();

        booking = new BookingEntity();
        booking.setId(bookingId);
        booking.setBookingCode("BK-123456");
        booking.setScheduleId(scheduleId);
        booking.setStudentId(studentId);
        booking.setMembershipId(UUID.randomUUID());
        booking.setStatus("CONFIRMED");
        booking.setMatNumber(3);

        schedule = new ClassScheduleEntity();
        schedule.setId(scheduleId);
        schedule.setBranchId(branchId);
        // Ca học bắt đầu sau 15 phút (trong cửa sổ 30 phút hợp lệ)
        schedule.setStartTime(Instant.now().plus(15, ChronoUnit.MINUTES));
        schedule.setEndTime(Instant.now().plus(75, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("Điểm danh thành công trong cửa sổ 30 phút trước giờ học")
    void testProcessCheckIn_Success_WithinWindow() {
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(attendanceRecordRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(attendanceRecordRepository.saveAndFlush(any(AttendanceRecordEntity.class))).thenAnswer(invocation -> {
            AttendanceRecordEntity a = invocation.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        CheckInDto.CheckInReq req = new CheckInDto.CheckInReq(bookingId, studentId, scheduleId, branchId, null, "QR_SCAN", null);
        com.company.yoga.identity.account.entity.UserEntity actor = new com.company.yoga.identity.account.entity.UserEntity(); actor.setId(studentId);
        org.mockito.Mockito.lenient().when(accessPolicy.actor()).thenReturn(actor);
        CheckInDto.CheckInResp resp = checkInService.processCheckIn(req);

        assertNotNull(resp);
        assertFalse(resp.alreadyCheckedIn());
        assertEquals("PRESENT", resp.attendanceStatus());
        assertEquals(3, resp.matNumber());
        assertEquals("ATTENDED", booking.getStatus());
        verify(attendanceRecordRepository).saveAndFlush(any(AttendanceRecordEntity.class));
        verify(bookingRepository).save(booking);
    }

    @Test
    @DisplayName("Idempotency Guard: Quét lại thẻ đã điểm danh trả về kết quả cũ mà không duplicate")
    void testProcessCheckIn_Idempotent_WhenAlreadyCheckedIn() {
        AttendanceRecordEntity existingRecord = new AttendanceRecordEntity();
        existingRecord.setId(UUID.randomUUID());
        existingRecord.setCheckedInAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        existingRecord.setAttendanceStatus("PRESENT");

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));
        when(attendanceRecordRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existingRecord));

        CheckInDto.CheckInReq req = new CheckInDto.CheckInReq(bookingId, studentId, scheduleId, branchId, null, "QR_SCAN", null);
        CheckInDto.CheckInResp resp = checkInService.processCheckIn(req);

        assertNotNull(resp);
        assertTrue(resp.alreadyCheckedIn());
        assertEquals("PRESENT", resp.attendanceStatus());
    }

    @Test
    @DisplayName("Báo lỗi CHECKIN_WINDOW_NOT_OPEN khi học viên đến quá sớm (> 30 phút)")
    void testProcessCheckIn_ThrowsWindowNotOpen_WhenTooEarly() {
        // Ca học bắt đầu sau 2 giờ
        schedule.setStartTime(Instant.now().plus(2, ChronoUnit.HOURS));
        schedule.setEndTime(Instant.now().plus(3, ChronoUnit.HOURS));

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(attendanceRecordRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));

        CheckInDto.CheckInReq req = new CheckInDto.CheckInReq(bookingId, studentId, scheduleId, branchId, null, "QR_SCAN", null);
        BusinessException ex = assertThrows(BusinessException.class, () -> checkInService.processCheckIn(req));

        assertEquals(ScheduleResultCodes.CHECKIN_WINDOW_NOT_OPEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("Báo lỗi CHECKIN_WINDOW_EXPIRED khi ca học đã kết thúc")
    void testProcessCheckIn_ThrowsWindowExpired_WhenClassEnded() {
        // Ca học đã kết thúc 10 phút trước
        schedule.setStartTime(Instant.now().minus(70, ChronoUnit.MINUTES));
        schedule.setEndTime(Instant.now().minus(10, ChronoUnit.MINUTES));

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(attendanceRecordRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(scheduleRepository.findByIdWithLock(scheduleId)).thenReturn(Optional.of(schedule));

        CheckInDto.CheckInReq req = new CheckInDto.CheckInReq(bookingId, studentId, scheduleId, branchId, null, "QR_SCAN", null);
        BusinessException ex = assertThrows(BusinessException.class, () -> checkInService.processCheckIn(req));

        assertEquals(ScheduleResultCodes.CHECKIN_WINDOW_EXPIRED, ex.getErrorCode());
    }
}
