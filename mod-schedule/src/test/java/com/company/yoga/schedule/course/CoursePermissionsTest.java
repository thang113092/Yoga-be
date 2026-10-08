package com.company.yoga.schedule.course;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.order.repository.*;
import com.company.yoga.schedule.booking.repository.*;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CoursePermissionsTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean CourseDao dao() { return mock(CourseDao.class); }
        @Bean CourseService service(CourseDao dao) { return new CourseService(dao,mock(AccessPolicy.class),mock(ClassScheduleService.class),
            mock(ClassScheduleRepository.class),mock(BookingRepository.class),mock(OrderRepository.class),mock(OrderItemRepository.class)); }
    }
    AnnotationConfigApplicationContext context;
    @BeforeEach void open() { context=new AnnotationConfigApplicationContext(Config.class); }
    @AfterEach void close() { SecurityContextHolder.clearContext();context.close(); }
    void role(String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("actor","",List.of(new SimpleGrantedAuthority("ROLE_"+role)))); }
    @Test void managerCannotCreateCourseProgram() {
        role("BRANCH_MANAGER"); assertThrows(AccessDeniedException.class,()->context.getBean(CourseService.class).createCourse(null));
        verifyNoInteractions(context.getBean(CourseDao.class));
    }
    @Test void receptionistCannotOpenClass() {
        role("RECEPTIONIST");assertThrows(AccessDeniedException.class,()->context.getBean(CourseService.class).createClass(null));
    }
    @Test void instructorCannotEnrollStudent() {
        role("INSTRUCTOR");assertThrows(AccessDeniedException.class,()->context.getBean(CourseService.class).enroll(UUID.randomUUID(),UUID.randomUUID()));
    }
    @Test void studentCannotReadClassRoster() {
        role("STUDENT");assertThrows(AccessDeniedException.class,()->context.getBean(CourseService.class).roster(UUID.randomUUID()));
    }
    @Test void adminCanCreateCourseProgram() {
        role("SUPER_ADMIN");var dao=context.getBean(CourseDao.class);
        when(dao.rows(anyString(),anyMap())).thenReturn(List.of(Map.of("name","Yoga")));
        assertEquals("Yoga",context.getBean(CourseService.class).createCourse(new CourseDto.CreateCourse("YG","Yoga","",UUID.randomUUID(),12,60,BigDecimal.TEN,java.util.stream.IntStream.rangeClosed(1,12).mapToObj(i -> new CourseDto.ProgramSession("Buổi " + i,"Nội dung học",60)).toList())).get("name"));
        verify(dao,times(13)).update(anyString(),anyMap());
    }
}
