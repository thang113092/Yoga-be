package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProvider.ToolSpec;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.dto.UserDto;
import com.company.yoga.identity.account.service.UserService;
import com.company.yoga.membership.plan.dto.MembershipDto;
import com.company.yoga.membership.plan.service.StudentMembershipService;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.service.BookingService;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only functions for receptionists and instructors. They go through the same services as the staff pages, so
 * the usual rules apply: a receptionist works in their branch, an instructor sees the roster of their own classes
 * only and cannot look students up. Phones are masked; e-mails, birthdays and health records are never returned.
 */
@Component
public class StaffTools {

    static final int MAX_DAYS = 14;
    static final int MAX_CLASSES = 30;

    private final ClassScheduleService schedules;
    private final BookingService bookings;
    private final StudentMembershipService memberships;
    private final UserService users;
    private final StudentTools studentTools;
    private final NamedParameterJdbcTemplate jdbc;

    public StaffTools(ClassScheduleService schedules, BookingService bookings, StudentMembershipService memberships,
                      UserService users, StudentTools studentTools, NamedParameterJdbcTemplate jdbc) {
        this.schedules = schedules;
        this.bookings = bookings;
        this.memberships = memberships;
        this.users = users;
        this.studentTools = studentTools;
        this.jdbc = jdbc;
    }

    public List<AiTool> tools(AssistantProfile.Caller caller) {
        if ("INSTRUCTOR".equals(caller.role())) {
            return List.of(studentTools.listBranches(), myTeachingSchedule(caller), classRoster());
        }
        if ("RECEPTIONIST".equals(caller.role())) {
            return List.of(studentTools.listBranches(), branchClasses(caller), classRoster(), findStudent(caller), studentPasses(),
                    studentBookings());
        }
        throw new BusinessException(CommonErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------------------- tools

    private static Map<String, Object> periodSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fromDate", Map.of("type", "string", "description", "Ngày bắt đầu, YYYY-MM-DD (giờ Việt Nam)"));
        props.put("toDate", Map.of("type", "string", "description", "Ngày cuối, YYYY-MM-DD, tính cả ngày này; bỏ trống là chỉ ngày bắt đầu. Tối đa 14 ngày"));
        return props;
    }

    AiTool myTeachingSchedule(AssistantProfile.Caller caller) {
        return tool(new ToolSpec("get_my_teaching_schedule",
                "Lịch dạy của huấn luyện viên đang hỏi trong khoảng ngày: lớp, chi nhánh, phòng, giờ, số học viên đã đặt / sức chứa, trạng thái buổi.",
                Map.of("type", "object", "properties", periodSchema(), "required", List.of("fromDate"))),
                args -> Map.of("classes", classes(args, null, caller.userId())));
    }

    AiTool branchClasses(AssistantProfile.Caller caller) {
        Map<String, Object> props = periodSchema();
        props.put("branchId", Map.of("type", "string", "description", "ID chi nhánh từ list_branches; bỏ trống là chi nhánh của lễ tân"));
        return tool(new ToolSpec("get_branch_classes",
                "Các buổi lớp của chi nhánh trong khoảng ngày (cả buổi đã diễn ra hôm nay): lớp, huấn luyện viên, phòng, giờ, đã đặt / sức chứa, trạng thái.",
                Map.of("type", "object", "properties", props, "required", List.of("fromDate"))),
                args -> {
                    UUID branch = ManagerReportTools.uuid(args.get("branchId"));
                    return Map.of("classes", classes(args, branch == null ? homeBranch(caller) : branch, null));
                });
    }

    AiTool classRoster() {
        return tool(new ToolSpec("get_class_roster",
                "Danh sách học viên đã đặt một buổi lớp: tên, 3 số cuối điện thoại, số thảm, trạng thái (đã đặt, đã điểm danh, vắng, đã hủy).",
                Map.of("type", "object", "properties", Map.of("scheduleId", Map.of("type", "string", "description", "scheduleId từ lịch lớp")),
                        "required", List.of("scheduleId"))),
                args -> {
                    UUID id = requiredId(args.get("scheduleId"), "scheduleId", "lịch lớp");
                    List<BookingDto.AttendeeResp> list = schedules.getScheduleAttendees(id);
                    return Map.of("total", list.size(), "attendees", list.stream().map(a -> row("name", a.studentName(),
                            "phoneEnding", maskPhone(a.studentPhone()), "mat", a.matNumber(), "status", a.status(),
                            "checkedInAt", a.checkedInAt() == null ? null : StudentTools.when(a.checkedInAt()))).toList());
                });
    }

    AiTool findStudent(AssistantProfile.Caller caller) {
        return tool(new ToolSpec("find_student",
                "Tìm học viên theo tên hoặc số điện thoại tại chi nhánh của lễ tân. Trả về studentId để tra thẻ và lịch.",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string", "description", "Tên hoặc số điện thoại")),
                        "required", List.of("query"))),
                args -> {
                    String q = ManagerReportTools.string(args.get("query"));
                    if (q == null || q.length() < 2) throw new IllegalArgumentException("Nhập ít nhất 2 ký tự để tìm");
                    List<UserDto.UserResponse> found = users.searchStudents(q, homeBranch(caller), 10);
                    return Map.of("students", found.stream().map(u -> row("studentId", u.id().toString(), "name", u.fullName(),
                            "phoneEnding", maskPhone(u.phone()), "branch", u.branchName(), "active", u.isActive())).toList());
                });
    }

    AiTool studentPasses() {
        return tool(new ToolSpec("get_student_passes",
                "Thẻ tập của một học viên: gói, trạng thái, ngày bắt đầu/kết thúc, số buổi còn, chi nhánh dùng được.",
                Map.of("type", "object", "properties", Map.of("studentId", Map.of("type", "string", "description", "studentId từ find_student")),
                        "required", List.of("studentId"))),
                args -> {
                    UUID id = requiredId(args.get("studentId"), "studentId", "find_student");
                    List<MembershipDto.Resp> list = memberships.getMembershipsByStudent(id);
                    Map<UUID, String> plans = names("SELECT id,name FROM yoga.membership_plans WHERE id IN (:ids)",
                            list.stream().map(MembershipDto.Resp::planId).filter(Objects::nonNull).toList());
                    return Map.of("passes", list.stream().map(m -> row("code", m.membershipCode(), "plan", plans.get(m.planId()),
                            "status", m.status(), "startDate", m.startDate() == null ? null : m.startDate().toString(),
                            "endDate", m.endDate() == null ? "không thời hạn" : m.endDate().toString(),
                            "remainingSessions", m.remainingSessions() == null ? "không giới hạn" : m.remainingSessions(),
                            "allBranches", m.isAllBranches(), "access", m.accessScope())).toList());
                });
    }

    AiTool studentBookings() {
        return tool(new ToolSpec("get_student_bookings",
                "Các buổi một học viên đã đặt (sắp tới và gần đây): lớp, chi nhánh, giờ, trạng thái.",
                Map.of("type", "object", "properties", Map.of("studentId", Map.of("type", "string", "description", "studentId từ find_student")),
                        "required", List.of("studentId"))),
                args -> {
                    UUID id = requiredId(args.get("studentId"), "studentId", "find_student");
                    return Map.of("bookings", bookings.getStudentBookingDetails(id).stream().limit(20).map(b -> row("className", b.className(),
                            "branch", b.branchName(), "startAt", StudentTools.when(b.startTime()), "status", b.status(),
                            "mode", b.deliveryMode())).toList());
                });
    }

    // ---------------------------------------------------------------- helpers

    private List<Map<String, Object>> classes(Map<String, Object> args, UUID branch, UUID instructor) {
        Object fromArg = args.getOrDefault("fromDate", "");
        Object toArg = ManagerReportTools.string(args.get("toDate")) == null ? fromArg : args.get("toDate");
        ManagerReportTools.Period period = ManagerReportTools.period(Map.of("startDate", fromArg, "endDate", toArg));
        if (ChronoUnit.DAYS.between(period.start(), period.end()) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("Mỗi lần xem tối đa " + MAX_DAYS + " ngày");
        }
        List<ScheduleDto.Resp> found = schedules.getSchedules(branch, instructor, null, period.from(), period.to()).stream()
                .sorted(Comparator.comparing(ScheduleDto.Resp::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_CLASSES).toList();
        return found.stream().map(s -> {
            Map<String, Object> m = StudentTools.classRow(s);
            m.put("status", s.status());
            m.put("booked", s.bookedCount());
            m.put("capacity", s.maxCapacity());
            m.put("started", s.startTime() != null && !s.startTime().isAfter(Instant.now()));
            return m;
        }).toList();
    }

    /** The receptionist's branch: home branch, else the first assigned one. */
    UUID homeBranch(AssistantProfile.Caller caller) {
        if (caller.homeBranchId() != null) return caller.homeBranchId();
        return jdbc.queryForList("SELECT branch_id FROM yoga.user_branches WHERE user_id=:me ORDER BY branch_id LIMIT 1",
                        Map.of("me", caller.userId()), UUID.class).stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    static String maskPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() < 3 ? "***" : "***" + digits.substring(digits.length() - 3);
    }

    private static UUID requiredId(Object value, String name, String source) {
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " không hợp lệ; hãy lấy từ " + source);
        }
    }

    private Map<UUID, String> names(String sql, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", ids), rs -> {
            out.put(rs.getObject("id", UUID.class), rs.getString("name"));
        });
        return out;
    }

    static Map<String, Object> row(Object... kv) {
        return ManagerReportTools.row(kv);
    }

    private static AiTool tool(ToolSpec spec, Function<Map<String, Object>, Map<String, Object>> body) {
        return new AiTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public Map<String, Object> run(Map<String, Object> args) {
                return body.apply(args == null ? Map.of() : args);
            }
        };
    }
}
