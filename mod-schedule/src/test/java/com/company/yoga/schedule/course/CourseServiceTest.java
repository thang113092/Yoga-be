package com.company.yoga.schedule.course;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.order.repository.*;
import com.company.yoga.schedule.booking.repository.*;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {
    @Mock CourseDao dao;
    @Mock AccessPolicy access;
    @Mock ClassScheduleService scheduleService;
    @Mock ClassScheduleRepository schedules;
    @Mock BookingRepository bookings;
    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    @InjectMocks CourseService service;
    UUID branch=UUID.randomUUID(),course=UUID.randomUUID(),room=UUID.randomUUID(),teacher=UUID.randomUUID();
    CourseDto.CreateClass request(List<CourseDto.Session> sessions) {
        return new CourseDto.CreateClass(course,branch,"C01","Yoga",10,new BigDecimal("500000"),sessions);
    }
    CourseDto.Session session(Instant start) { return new CourseDto.Session(room,teacher,start,start.plusSeconds(3600),"Buổi học"); }
    void validResources() {
        when(dao.rows(anyString(),anyMap())).thenReturn(List.of(Map.of("totalSessions",2)));
        when(dao.count(anyString(),anyMap())).thenReturn(1L);
    }
    @Test void rejectsIncompleteProgramPlanBeforeWriting() {
        var plan=List.of(new CourseDto.ProgramSession("Buổi 1","Hít thở",45));
        assertThrows(BusinessException.class,()->service.createCourse(new CourseDto.CreateCourse("P1","Yoga","",course,2,60,BigDecimal.TEN,plan)));
        verify(dao,never()).update(anyString(),anyMap());
    }
    @Test void rejectsBlankProgramContentBeforeWriting() {
        var plan=List.of(new CourseDto.ProgramSession("Buổi 1"," ",45));
        assertThrows(BusinessException.class,()->service.createCourse(new CourseDto.CreateCourse("P1","Yoga","",course,1,60,BigDecimal.TEN,plan)));
        verify(dao,never()).update(anyString(),anyMap());
    }
    @Test void detectsOverlapsBetweenSessionsInSameRequest() {
        validResources(); Instant start=Instant.now().plusSeconds(86400);
        var conflicts=service.preview(request(List.of(session(start),session(start.plusSeconds(1800)))));
        assertEquals(1,conflicts.size());assertEquals(2,conflicts.getFirst().sessionNumber());
        verify(access).requireStaffBranch(branch);
    }
    @Test void adjacentSessionsAreAllowed() {
        validResources();Instant start=Instant.now().plusSeconds(86400);
        assertTrue(service.preview(request(List.of(session(start),session(start.plusSeconds(3600))))).isEmpty());
    }
    @Test void catchesExistingStandaloneInstructorAndRoomConflicts() {
        validResources();when(schedules.existsOverlappingRoomSchedule(eq(room),any(),any())).thenReturn(true);
        when(schedules.existsOverlappingInstructorSchedule(eq(teacher),any(),any())).thenReturn(true);
        Instant start=Instant.now().plusSeconds(86400);
        assertEquals(4,service.preview(request(List.of(session(start),session(start.plusSeconds(7200))))).size());
    }
    @Test void refusesPartialCourseBeforeAnyWrites() {
        when(dao.rows(anyString(),anyMap())).thenReturn(List.of(Map.of("totalSessions",12)));
        assertThrows(BusinessException.class,()->service.createClass(request(List.of(session(Instant.now().plusSeconds(86400))))));
        verify(dao,never()).update(anyString(),anyMap());verifyNoInteractions(scheduleService);
    }
}
