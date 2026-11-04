package com.company.yoga.schedule.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.entity.AttendanceRecordEntity;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.repository.AttendanceRecordRepository;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OnlineClassServiceTest {

    @Mock private AccessPolicy accessPolicy;
    @Mock private BookingRepository bookings;
    @Mock private ClassScheduleRepository schedules;
    @Mock private AttendanceRecordRepository attendance;
    @Mock private OnlineMeetingSyncService sync;
    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private PlatformTransactionManager txManager;

    private OnlineClassService service;
    private UserEntity student;
    private BookingEntity booking;
    private ClassScheduleEntity schedule;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OnlineClassService(accessPolicy, bookings, schedules, attendance, sync, jdbc, new TransactionTemplate(txManager), 15);
        student = new UserEntity();
        student.setId(UUID.randomUUID());
        schedule = new ClassScheduleEntity();
        schedule.setId(UUID.randomUUID());
        schedule.setBranchId(UUID.randomUUID());
        schedule.setStatus("SCHEDULED");
        schedule.setDeliveryMode("ONLINE");
        startsIn(Duration.ofMinutes(10));
        booking = new BookingEntity();
        booking.setId(UUID.randomUUID());
        booking.setScheduleId(schedule.getId());
        booking.setStudentId(student.getId());
        booking.setMembershipId(UUID.randomUUID());
        booking.setStatus("CONFIRMED");
        lenient().when(accessPolicy.actor()).thenReturn(student);
        lenient().when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        lenient().when(schedules.findById(schedule.getId())).thenReturn(Optional.of(schedule));
        lenient().when(schedules.findByIdWithLock(schedule.getId())).thenReturn(Optional.of(schedule));
        lenient().when(sync.isConfigured()).thenReturn(true);
    }

    private void startsIn(Duration lead) {
        schedule.setStartTime(Instant.now().plus(lead));
        schedule.setEndTime(schedule.getStartTime().plus(Duration.ofHours(1)));
    }

    private void assertCode(Runnable action, Object code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("Vào lớp trong cửa sổ: trả link riêng, ghi điểm danh ONLINE_JOIN do chính học viên, booking ATTENDED")
    void join_recordsAttendance() {
        when(sync.ensureJoinUrl(booking.getId())).thenReturn(Optional.of("https://zoom.us/w/1?tk=abc"));
        when(attendance.findByBookingId(booking.getId())).thenReturn(Optional.empty());

        var resp = service.join(booking.getId());

        assertThat(resp.joinUrl()).isEqualTo("https://zoom.us/w/1?tk=abc");
        ArgumentCaptor<AttendanceRecordEntity> saved = ArgumentCaptor.forClass(AttendanceRecordEntity.class);
        verify(attendance).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCheckInMethod()).isEqualTo("ONLINE_JOIN");
        assertThat(saved.getValue().getCheckedInBy()).isEqualTo(student.getId());
        assertThat(saved.getValue().getCheckedInBranchId()).isEqualTo(schedule.getBranchId());
        assertThat(booking.getStatus()).isEqualTo("ATTENDED");
    }

    @Test
    @DisplayName("Vào lại lớp (rớt mạng) chỉ trả link, không ghi điểm danh lần hai")
    void rejoin_doesNotRecordTwice() {
        booking.setStatus("ATTENDED");
        when(sync.ensureJoinUrl(booking.getId())).thenReturn(Optional.of("https://zoom.us/w/1"));

        assertThat(service.join(booking.getId()).joinUrl()).isEqualTo("https://zoom.us/w/1");
        verify(attendance, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Không ai khác được lấy link vào lớp của học viên")
    void otherUser_forbidden() {
        UserEntity other = new UserEntity();
        other.setId(UUID.randomUUID());
        when(accessPolicy.actor()).thenReturn(other);
        assertCode(() -> service.join(booking.getId()), CommonErrorCode.FORBIDDEN);
        verify(sync, never()).ensureJoinUrl(any());
    }

    @Test
    @DisplayName("Chưa đến giờ (trước 15 phút) hoặc lớp đã kết thúc thì không trả link")
    void outsideWindow() {
        startsIn(Duration.ofMinutes(20));
        assertCode(() -> service.join(booking.getId()), ScheduleResultCodes.ONLINE_JOIN_NOT_OPEN);
        startsIn(Duration.ofMinutes(-61));
        assertCode(() -> service.join(booking.getId()), ScheduleResultCodes.ONLINE_CLASS_ENDED);
        verify(sync, never()).ensureJoinUrl(any());
    }

    @Test
    @DisplayName("Lớp tại phòng, booking đã hủy, Zoom chưa cấu hình hoặc chưa tạo được link đều bị từ chối")
    void rejections() {
        schedule.setDeliveryMode("IN_PERSON");
        assertCode(() -> service.join(booking.getId()), ScheduleResultCodes.NOT_ONLINE_CLASS);
        schedule.setDeliveryMode("ONLINE");
        booking.setStatus("CANCELLED");
        assertCode(() -> service.join(booking.getId()), ScheduleResultCodes.BOOKING_NOT_CONFIRMED);
        booking.setStatus("CONFIRMED");
        when(sync.isConfigured()).thenReturn(false);
        assertCode(() -> service.join(booking.getId()), ScheduleResultCodes.ONLINE_NOT_CONFIGURED);
        when(sync.isConfigured()).thenReturn(true);
        when(sync.ensureJoinUrl(booking.getId())).thenReturn(Optional.empty());
        assertCode(() -> service.join(booking.getId()), ScheduleResultCodes.ONLINE_ROOM_NOT_READY);
        verify(attendance, never()).saveAndFlush(any());
        verify(jdbc, never()).update(anyString(), anyMap());
    }
}
