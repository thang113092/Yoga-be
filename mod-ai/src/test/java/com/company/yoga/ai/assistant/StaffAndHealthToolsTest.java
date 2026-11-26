package com.company.yoga.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.ai.AiProvider;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.dto.UserDto;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.identity.account.service.UserService;
import com.company.yoga.membership.plan.service.StudentMembershipService;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.service.BookingService;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class StaffAndHealthToolsTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();

    private final ClassScheduleService schedules = mock(ClassScheduleService.class);
    private final UserService users = mock(UserService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StudentTools studentTools = mock(StudentTools.class);
    {
        when(studentTools.listBranches()).thenReturn(new AiTool() {
            public AiProvider.ToolSpec spec() {
                return new AiProvider.ToolSpec("list_branches", "Chi nhánh", null);
            }

            public Map<String, Object> run(Map<String, Object> args) {
                return Map.of();
            }
        });
    }
    private final StaffTools staff = new StaffTools(schedules, mock(BookingService.class), mock(StudentMembershipService.class), users,
            studentTools, jdbc);

    private static List<String> names(List<AiTool> tools) {
        return tools.stream().map(t -> t.spec().name()).toList();
    }

    private static AssistantProfile.Caller caller(String role) {
        return new AssistantProfile.Caller(ME, role, BRANCH);
    }

    @Test
    void instructorsCannotLookStudentsUp() {
        assertThat(names(staff.tools(caller("INSTRUCTOR")))).contains("get_my_teaching_schedule", "get_class_roster")
                .doesNotContain("find_student", "get_student_passes", "get_branch_classes");
        assertThat(names(staff.tools(caller("RECEPTIONIST")))).contains("find_student", "get_student_passes", "get_branch_classes",
                "get_class_roster").doesNotContain("get_my_teaching_schedule");
        assertThatThrownBy(() -> staff.tools(caller("STUDENT"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void rosterMasksPhonesAndDropsEmails() {
        UUID schedule = UUID.randomUUID();
        when(schedules.getScheduleAttendees(schedule)).thenReturn(List.of(new BookingDto.AttendeeResp(UUID.randomUUID(), "BK1", UUID.randomUUID(),
                "Nguyễn Thị Hoa", "0912 345 678", "hoa@example.com", "F", 3, "CONFIRMED", Instant.now(), null)));
        AiTool roster = staff.tools(caller("INSTRUCTOR")).stream().filter(t -> t.spec().name().equals("get_class_roster")).findFirst().orElseThrow();

        String out = roster.run(Map.of("scheduleId", schedule.toString())).toString();

        assertThat(out).contains("Nguyễn Thị Hoa").contains("***678").doesNotContain("0912").doesNotContain("hoa@example.com");
    }

    @Test
    void receptionistSearchesTheirOwnBranch() {
        when(users.searchStudents("Hoa", BRANCH, 10)).thenReturn(List.of(new UserDto.UserResponse(UUID.randomUUID(), "0987654321",
                "x@y.z", "Trần Hoa", "F", null, null, "STUDENT", "Học viên", BRANCH, "Cầu Giấy", true, Instant.now())));
        AiTool find = staff.findStudent(caller("RECEPTIONIST"));
        String out = find.run(Map.of("query", "Hoa")).toString();
        assertThat(out).contains("Trần Hoa").contains("***321").doesNotContain("x@y.z");
        assertThatThrownBy(() -> find.run(Map.of("query", "H"))).hasMessageContaining("2 ký tự");
    }

    @Test
    void maskPhoneKeepsOnlyTheLastThreeDigits() {
        assertThat(StaffTools.maskPhone("+84 912-345-678")).isEqualTo("***678");
        assertThat(StaffTools.maskPhone("12")).isEqualTo("***");
        assertThat(StaffTools.maskPhone(null)).isNull();
    }

    // ---------------------------------------------------------------- health

    private HealthTools health(boolean consented) {
        AccessPolicy access = mock(AccessPolicy.class);
        UserEntity user = new UserEntity();
        user.setId(ME);
        when(access.actor()).thenReturn(user);
        when(access.role(user)).thenReturn("STUDENT");
        HealthConsentService consents = mock(HealthConsentService.class);
        when(consents.consented(ME)).thenReturn(consented);
        return new HealthTools(consents, access, jdbc);
    }

    private static Map<String, Object> video(String title, boolean pregnancy, boolean heart, boolean injuries) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("_pregnancy", pregnancy);
        m.put("_heart", heart);
        m.put("_injuries", injuries);
        return m;
    }

    @SuppressWarnings("unchecked")
    @Test
    void unsuitableVideosAreRemovedBeforeTheModelSeesThem() {
        HealthTools tools = health(true);
        when(jdbc.query(anyString(), eq(Map.of("me", ME)), any(RowMapper.class)))
                .thenReturn(List.of(new HealthTools.Flags(true, false, false, true)));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(new ArrayList<>(List.of(
                video("Yoga bầu nhẹ nhàng", false, false, false),
                video("Vinyasa đảo ngược", true, true, false),
                video("Thở thư giãn", false, false, true))));

        Map<String, Object> out = tools.recommendVideos().run(Map.of());

        List<Map<String, Object>> videos = (List<Map<String, Object>>) out.get("videos");
        assertThat(videos).extracting(v -> v.get("title")).containsExactly("Yoga bầu nhẹ nhàng", "Thở thư giãn");
        assertThat(videos.get(0)).doesNotContainKeys("_pregnancy", "_heart", "_injuries");
        assertThat(out).containsEntry("excludedAsUnsuitable", 1);
    }

    @Test
    void withoutConsentNothingIsRead() {
        HealthTools tools = health(false);
        assertThatThrownBy(() -> tools.myHealthFlags().run(Map.of())).hasMessageContaining("Gợi ý theo sức khỏe");
        assertThatThrownBy(() -> tools.recommendVideos().run(Map.of())).isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).query(anyString(), any(Map.class), any(RowMapper.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void flagsAreOnlyThreeYesNoItems() {
        HealthTools tools = health(true);
        when(jdbc.query(anyString(), eq(Map.of("me", ME)), any(RowMapper.class)))
                .thenReturn(List.of(new HealthTools.Flags(true, true, false, false)));
        assertThat(tools.myHealthFlags().run(Map.of())).containsOnlyKeys("profile", "injuries", "heartOrBloodPressure", "pregnant");
        assertThat(tools.tools()).extracting(t -> ((AiTool) t).spec().name()).containsExactly("get_my_health_flags", "recommend_videos_for_me");
        assertThat(AiProvider.Role.values()).isNotEmpty();
    }
}
