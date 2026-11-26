package com.company.yoga.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.VideoLibraryService;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.plan.service.StudentMembershipService;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.service.BookingService;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import com.company.yoga.schedule.course.CourseService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class StudentToolsTest {

    private static final UUID ME = UUID.randomUUID();
    private final ClassScheduleService schedules = mock(ClassScheduleService.class);
    private final BookingService bookings = mock(BookingService.class);
    private final AccessPolicy access = mock(AccessPolicy.class);
    private final StudentTools tools = new StudentTools(schedules, bookings, mock(StudentMembershipService.class),
            mock(CourseService.class), mock(VideoLibraryService.class), access, mock(NamedParameterJdbcTemplate.class));

    private void signedInAs(String role) {
        UserEntity user = new UserEntity();
        user.setId(ME);
        when(access.actor()).thenReturn(user);
        when(access.role(user)).thenReturn(role);
    }

    @BeforeEach
    void setUp() {
        signedInAs("STUDENT");
        when(bookings.getStudentBookingDetails(ME)).thenReturn(List.of());
    }

    private static ScheduleDto.Resp schedule(UUID id, String name, Instant start, int seats, String status, UUID course) {
        return new ScheduleDto.Resp(id, UUID.randomUUID(), "Cầu Giấy", UUID.randomUUID(), UUID.randomUUID(), name, "HLV Mai", "Phòng 1",
                start, start.plus(Duration.ofHours(1)), 10, 10 - seats, seats, status, course, null, "IN_PERSON", null, 0, 0);
    }

    @Test
    void searchFindsUpcomingClassesIgnoringAccents() {
        Instant later = Instant.now().plus(Duration.ofHours(5));
        when(schedules.getSchedules(isNull(), isNull(), isNull(), any(), any())).thenReturn(List.of(
                schedule(UUID.randomUUID(), "Hatha cơ bản", later.plus(Duration.ofHours(1)), 3, "SCHEDULED", null),
                schedule(UUID.randomUUID(), "Hatha nâng cao", later, 0, "SCHEDULED", null),
                schedule(UUID.randomUUID(), "Hatha đã hủy", later, 5, "CANCELLED", null),
                schedule(UUID.randomUUID(), "Yin Yoga", later, 5, "SCHEDULED", null)));
        String today = LocalDate.now(StudentTools.VIETNAM).toString();

        @SuppressWarnings("unchecked")
        var classes = (List<Map<String, Object>>) tools.searchClasses().run(Map.of("fromDate", today, "toDate",
                LocalDate.now(StudentTools.VIETNAM).plusDays(1).toString(), "classType", "HÁTHA")).get("classes");

        assertThat(classes).extracting(c -> c.get("className")).containsExactly("Hatha nâng cao", "Hatha cơ bản");
        assertThat(classes.get(1)).containsEntry("seatsLeft", 3).containsEntry("room", "Phòng 1").containsKey("scheduleId");
    }

    @Test
    void searchIsLimitedToTwoWeeks() {
        String today = LocalDate.now(StudentTools.VIETNAM).toString();
        assertThatThrownBy(() -> tools.searchClasses().run(Map.of("fromDate", today,
                "toDate", LocalDate.now(StudentTools.VIETNAM).plusDays(20).toString()))).hasMessageContaining("14");
    }

    @Test
    void proposeOffersAButtonButNeverBooks() {
        UUID id = UUID.randomUUID();
        when(schedules.getScheduleById(id)).thenReturn(schedule(id, "Hatha", Instant.now().plus(Duration.ofDays(1)), 2, "SCHEDULED", null));

        Map<String, Object> out = tools.proposeBooking().run(Map.of("scheduleId", id.toString()));

        assertThat(out).containsEntry("ok", true);
        assertThat(out.get(AssistantService.ACTION)).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "BOOK_CLASS").containsEntry("scheduleId", id.toString());
        verify(bookings, never()).createBooking(any());
    }

    @Test
    void proposeRefusesFullPastCourseAndAlreadyBookedClasses() {
        UUID full = UUID.randomUUID();
        UUID past = UUID.randomUUID();
        UUID course = UUID.randomUUID();
        UUID booked = UUID.randomUUID();
        Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
        when(schedules.getScheduleById(full)).thenReturn(schedule(full, "A", tomorrow, 0, "SCHEDULED", null));
        when(schedules.getScheduleById(past)).thenReturn(schedule(past, "B", Instant.now().minus(Duration.ofHours(2)), 3, "SCHEDULED", null));
        when(schedules.getScheduleById(course)).thenReturn(schedule(course, "C", tomorrow, 3, "SCHEDULED", UUID.randomUUID()));
        when(schedules.getScheduleById(booked)).thenReturn(schedule(booked, "D", tomorrow, 3, "SCHEDULED", null));
        when(bookings.getStudentBookingDetails(ME)).thenReturn(List.of(new BookingDto.StudentBookingDetailResp(UUID.randomUUID(), "BK1",
                booked, "D", "HLV", "P1", "Cầu Giấy", tomorrow, tomorrow, 1, "CONFIRMED", Instant.now(), true, "IN_PERSON", "IN_PERSON")));

        assertThat(tools.proposeBooking().run(Map.of("scheduleId", full.toString()))).containsEntry("ok", false).doesNotContainKey(AssistantService.ACTION);
        assertThat(tools.proposeBooking().run(Map.of("scheduleId", past.toString()))).containsEntry("ok", false);
        assertThat(tools.proposeBooking().run(Map.of("scheduleId", course.toString())).get("reason").toString()).contains("khóa học");
        assertThat(tools.proposeBooking().run(Map.of("scheduleId", booked.toString())).get("reason").toString()).contains("đã đặt");
        assertThatThrownBy(() -> tools.proposeBooking().run(Map.of("scheduleId", "lớp hatha"))).hasMessageContaining("search_classes");
    }

    @Test
    void onlyStudentsCanUseTheseTools() {
        signedInAs("RECEPTIONIST");
        assertThatThrownBy(() -> tools.myBookings().run(Map.of())).isInstanceOf(BusinessException.class);
        verify(bookings, never()).getStudentBookingDetails(eq(ME));
    }

    @Test
    void normalizeDropsVietnameseMarks() {
        assertThat(StudentTools.normalize("  Đẳng Lực Hatha ")).isEqualTo("dang luc hatha");
    }
}
