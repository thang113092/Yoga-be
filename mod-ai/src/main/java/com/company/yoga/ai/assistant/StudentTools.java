package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProvider.ToolSpec;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.VideoDto;
import com.company.yoga.content.VideoLibraryService;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.plan.dto.MembershipDto;
import com.company.yoga.membership.plan.service.StudentMembershipService;
import com.company.yoga.schedule.booking.dto.BookingDto;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.service.BookingService;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import com.company.yoga.schedule.course.CourseService;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only functions for the student assistant. Everything runs as the signed-in student through the same services
 * as the student pages, so a student only ever sees their own passes and bookings. Booking itself is never done
 * here: {@code propose_booking} checks a class and offers a button that opens the normal booking dialog.
 */
@Component
public class StudentTools {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_SEARCH_DAYS = 14;
    static final int MAX_CLASSES = 25;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy HH:mm", Locale.forLanguageTag("vi"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ClassScheduleService schedules;
    private final BookingService bookings;
    private final StudentMembershipService memberships;
    private final CourseService courses;
    private final VideoLibraryService videos;
    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public StudentTools(ClassScheduleService schedules, BookingService bookings, StudentMembershipService memberships,
                        CourseService courses, VideoLibraryService videos, AccessPolicy accessPolicy, NamedParameterJdbcTemplate jdbc) {
        this.schedules = schedules;
        this.bookings = bookings;
        this.memberships = memberships;
        this.courses = courses;
        this.videos = videos;
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
    }

    public List<AiTool> tools() {
        return List.of(listBranches(), searchClasses(), myPasses(), myBookings(), searchCourses(), searchVideos(), proposeBooking());
    }

    /** The signed-in student; any other role is refused. */
    UUID me() {
        UserEntity actor = accessPolicy.actor();
        if (!"STUDENT".equals(accessPolicy.role(actor))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return actor.getId();
    }

    // ---------------------------------------------------------------- tools

    AiTool listBranches() {
        return tool(new ToolSpec("list_branches", "Các chi nhánh của An Yên (id, tên, địa chỉ). Gọi khi học viên nhắc tên chi nhánh để lấy branchId.", null),
                args -> Map.of("branches", jdbc.query("SELECT id,name,address FROM yoga.branches WHERE is_active ORDER BY name", Map.of(),
                        (rs, i) -> row("id", rs.getObject("id", UUID.class).toString(), "name", rs.getString("name"), "address", rs.getString("address")))));
    }

    AiTool searchClasses() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fromDate", Map.of("type", "string", "description", "Ngày bắt đầu tìm, YYYY-MM-DD (giờ Việt Nam)"));
        props.put("toDate", Map.of("type", "string", "description", "Ngày cuối, YYYY-MM-DD, tính cả ngày này; bỏ trống là chỉ ngày bắt đầu. Tối đa 14 ngày"));
        props.put("branchId", Map.of("type", "string", "description", "ID chi nhánh từ list_branches; bỏ trống là mọi chi nhánh"));
        props.put("classType", Map.of("type", "string", "description", "Tên bộ môn cần tìm, ví dụ Hatha, Yin, Vinyasa; bỏ trống là mọi bộ môn"));
        props.put("mode", Map.of("type", "string", "enum", List.of("IN_PERSON", "ONLINE"), "description", "Học tại phòng hay online; bỏ trống là cả hai"));
        return tool(new ToolSpec("search_classes",
                "Tìm các buổi lớp sắp diễn ra (chưa bắt đầu) theo ngày, chi nhánh, bộ môn: giờ học, huấn luyện viên, phòng, số chỗ còn trống. "
                        + "Buổi thuộc khóa học (courseClass=true) không đặt lẻ được.",
                Map.of("type", "object", "properties", props, "required", List.of("fromDate"))),
                args -> {
                    me();
                    Object fromArg = args.getOrDefault("fromDate", "");
                    Object toArg = ManagerReportTools.string(args.get("toDate")) == null ? fromArg : args.get("toDate");
                    ManagerReportTools.Period period = ManagerReportTools.period(Map.of("startDate", fromArg, "endDate", toArg));
                    LocalDate from = period.start();
                    LocalDate to = period.end();
                    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_SEARCH_DAYS) {
                        throw new IllegalArgumentException("Mỗi lần tìm tối đa " + MAX_SEARCH_DAYS + " ngày");
                    }
                    Instant now = Instant.now();
                    Instant start = from.atStartOfDay(VIETNAM).toInstant();
                    if (start.isBefore(now)) start = now;
                    Instant end = to.plusDays(1).atStartOfDay(VIETNAM).toInstant();
                    if (!end.isAfter(start)) return Map.of("classes", List.of(), "note", "Khoảng ngày đã qua");
                    String type = normalize(ManagerReportTools.string(args.get("classType")));
                    List<ScheduleDto.Resp> found = schedules.getSchedules(ManagerReportTools.uuid(args.get("branchId")), null,
                                    ManagerReportTools.string(args.get("mode")), start, end).stream()
                            .filter(s -> "SCHEDULED".equals(s.status()) && s.startTime() != null && s.startTime().isAfter(now))
                            .filter(s -> type == null || normalize(s.className()).contains(type))
                            .sorted(Comparator.comparing(ScheduleDto.Resp::startTime))
                            .toList();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("classes", found.stream().limit(MAX_CLASSES).map(StudentTools::classRow).toList());
                    if (found.size() > MAX_CLASSES) out.put("note", "Còn " + (found.size() - MAX_CLASSES) + " buổi nữa; hãy hỏi hẹp hơn");
                    return out;
                });
    }

    AiTool myPasses() {
        return tool(new ToolSpec("get_my_passes",
                "Thẻ tập của học viên đang hỏi: tên gói, trạng thái, ngày bắt đầu/kết thúc, số buổi còn lại, dùng ở chi nhánh nào, học tại phòng hay online.", null),
                args -> {
                    UUID me = me();
                    List<MembershipDto.Resp> list = memberships.getMembershipsByStudent(me);
                    Map<UUID, String> plans = names("SELECT id,name FROM yoga.membership_plans WHERE id IN (:ids)",
                            list.stream().map(MembershipDto.Resp::planId).filter(Objects::nonNull).toList());
                    Map<UUID, String> branches = names("SELECT id,name FROM yoga.branches WHERE id IN (:ids)",
                            list.stream().map(MembershipDto.Resp::registeredBranchId).filter(Objects::nonNull).toList());
                    return Map.of("passes", list.stream()
                            .filter(m -> !"CANCELLED".equals(m.status()) && !"TRANSFERRED".equals(m.status()))
                            .map(m -> row("code", m.membershipCode(), "plan", plans.get(m.planId()), "status", m.status(),
                                    "startDate", str(m.startDate()), "endDate", m.endDate() == null ? "không thời hạn" : m.endDate().toString(),
                                    "remainingSessions", m.remainingSessions() == null ? "không giới hạn" : m.remainingSessions(),
                                    "branches", Boolean.TRUE.equals(m.isAllBranches()) ? "Tất cả chi nhánh" : branches.get(m.registeredBranchId()),
                                    "access", m.accessScope()))
                            .toList());
                });
    }

    AiTool myBookings() {
        return tool(new ToolSpec("get_my_bookings",
                "Các buổi học viên đã đặt (sắp tới và gần đây): lớp, chi nhánh, giờ học, trạng thái, còn hủy được không.", null),
                args -> {
                    UUID me = me();
                    List<BookingDto.StudentBookingDetailResp> list = bookings.getStudentBookingDetails(me);
                    return Map.of("bookings", list.stream().limit(20).map(b -> row("className", b.className(), "branch", b.branchName(),
                            "room", b.roomName(), "instructor", b.instructorName(), "startAt", when(b.startTime()),
                            "status", b.status(), "canCancel", b.canCancel(), "mode", b.deliveryMode())).toList());
                });
    }

    AiTool searchCourses() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("branchId", Map.of("type", "string", "description", "ID chi nhánh từ list_branches; bỏ trống là mọi chi nhánh"));
        props.put("mode", Map.of("type", "string", "enum", List.of("IN_PERSON", "ONLINE"), "description", "Học tại phòng hay online"));
        return tool(new ToolSpec("search_courses",
                "Các lớp khóa học (học theo lộ trình nhiều buổi) đang mở đăng ký hoặc đang học: tên khóa, chi nhánh, số buổi, học phí, ngày bắt đầu/kết thúc, chỗ còn.",
                Map.of("type", "object", "properties", props)),
                args -> {
                    me();
                    List<Map<String, Object>> rows = courses.classes(ManagerReportTools.uuid(args.get("branchId")), ManagerReportTools.string(args.get("mode")));
                    return Map.of("courses", rows.stream()
                            .filter(c -> "OPEN".equals(c.get("status")) || "IN_PROGRESS".equals(c.get("status")))
                            .limit(20)
                            .map(c -> {
                                long capacity = num(c.get("maxCapacity"));
                                long enrolled = num(c.get("enrolledCount"));
                                return row("course", c.get("courseName"), "className", c.get("name"), "branch", c.get("branchName"),
                                        "status", c.get("status"), "mode", c.get("deliveryMode"), "sessions", c.get("plannedSessions"),
                                        "tuitionFee", c.get("tuitionFee"), "startAt", c.get("startTime") instanceof Instant i ? when(i) : null,
                                        "endAt", c.get("endTime") instanceof Instant i ? when(i) : null, "seatsLeft", Math.max(0, capacity - enrolled));
                            }).toList(),
                            "howToEnroll", "Đăng ký ở trang Khóa Học");
                });
    }

    AiTool searchVideos() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "Từ khóa trong tên hoặc mô tả video"));
        props.put("level", Map.of("type", "string", "enum", List.of("BEGINNER", "INTERMEDIATE", "ADVANCED", "ALL_LEVELS")));
        props.put("tag", Map.of("type", "string", "description", "Mã chủ đề, ví dụ STRETCHING, SPINE, MEDITATION, BREATHING, PRENATAL, STRENGTH, RELAX"));
        props.put("maxMinutes", Map.of("type", "integer", "description", "Thời lượng tối đa (phút)"));
        return tool(new ToolSpec("search_videos",
                "Tìm video bài tập trong thư viện online: tên, cấp độ, thời lượng, chủ đề, video có bị khóa (cần gói online) hay không.",
                Map.of("type", "object", "properties", props)),
                args -> {
                    me();
                    Integer minutes = args.get("maxMinutes") instanceof Number n ? n.intValue() : null;
                    VideoDto.LibraryPage page = videos.list(ManagerReportTools.string(args.get("level")), ManagerReportTools.string(args.get("tag")),
                            minutes, ManagerReportTools.string(args.get("query")), false, 0, 10);
                    return Map.of("total", page.total(), "videos", page.items().stream().map(v -> row("title", v.title(), "level", v.level(),
                            "minutes", v.durationSeconds() == null ? null : Math.round(v.durationSeconds() / 60.0), "tags", v.tags(),
                            "locked", v.locked(), "freePreview", v.isFreePreview(), "completed", v.completed())).toList(),
                            "howToWatch", "Xem ở trang Thư Viện Video");
                });
    }

    AiTool proposeBooking() {
        return tool(new ToolSpec("propose_booking",
                "Khi học viên muốn đặt một buổi cụ thể: kiểm tra buổi đó còn đặt được không và hiện nút 'Đặt chỗ' cho học viên tự bấm. "
                        + "Hàm này KHÔNG đặt chỗ.",
                Map.of("type", "object", "properties", Map.of("scheduleId", Map.of("type", "string", "description", "scheduleId lấy từ search_classes")),
                        "required", List.of("scheduleId"))),
                args -> {
                    UUID me = me();
                    UUID id;
                    try {
                        id = UUID.fromString(String.valueOf(args.get("scheduleId")).trim());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("scheduleId không hợp lệ; hãy lấy từ search_classes");
                    }
                    ScheduleDto.Resp s = schedules.getScheduleById(id);
                    if (s.courseClassId() != null) return Map.of("ok", false, "reason", "Buổi này thuộc khóa học, cần đăng ký khóa ở trang Khóa Học");
                    if (!"SCHEDULED".equals(s.status()) || !s.startTime().isAfter(Instant.now())) {
                        return Map.of("ok", false, "reason", "Buổi này đã bắt đầu, đã kết thúc hoặc đã hủy");
                    }
                    boolean booked = bookings.getStudentBookingDetails(me).stream()
                            .anyMatch(b -> id.equals(b.scheduleId()) && "CONFIRMED".equals(b.status()));
                    if (booked) return Map.of("ok", false, "reason", "Bạn đã đặt buổi này rồi");
                    int seats = (s.availableSlots() == null ? 0 : s.availableSlots())
                            + Math.max(0, (s.onlineCapacity() == null ? 0 : s.onlineCapacity()) - (s.onlineBookedCount() == null ? 0 : s.onlineBookedCount()));
                    if (seats <= 0) return Map.of("ok", false, "reason", "Buổi này đã hết chỗ; có thể vào hàng chờ ở trang Lịch & Đặt Chỗ");
                    String label = s.className() + " – " + when(s.startTime()) + " – " + s.branchName();
                    Map<String, Object> action = row("type", "BOOK_CLASS", "scheduleId", id.toString(), "label", label);
                    return row("ok", true, "class", classRow(s), "note",
                            "Đã hiện nút Đặt chỗ; học viên bấm nút để chọn thẻ và xác nhận. Chưa đặt chỗ.", AssistantService.ACTION, action);
                });
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> classRow(ScheduleDto.Resp s) {
        Map<String, Object> m = row("scheduleId", s.id().toString(), "className", s.className(), "branch", s.branchName(),
                "instructor", s.instructorName(), "startAt", when(s.startTime()),
                "endAt", s.endTime() == null ? null : TIME.format(s.endTime().atZone(VIETNAM)), "mode", s.deliveryMode(),
                "seatsLeft", s.availableSlots(), "courseClass", s.courseClassId() != null);
        if ("IN_PERSON".equals(s.deliveryMode()) || "HYBRID".equals(s.deliveryMode())) m.put("room", s.roomName());
        if (s.onlineCapacity() != null && s.onlineCapacity() > 0 && !"ONLINE".equals(s.deliveryMode())) {
            m.put("onlineSeatsLeft", Math.max(0, s.onlineCapacity() - (s.onlineBookedCount() == null ? 0 : s.onlineBookedCount())));
        }
        return m;
    }

    static String when(Instant t) {
        return t == null ? null : WHEN.format(t.atZone(VIETNAM));
    }

    /** Lower case without Vietnamese accents, so "hatha" finds "Hatha cơ bản" and "yin" finds "Yin Yoga". */
    static String normalize(String s) {
        if (s == null) return null;
        String noMarks = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D');
        return noMarks.toLowerCase(Locale.ROOT).trim();
    }

    private Map<UUID, String> names(String sql, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", ids), rs -> {
            out.put(rs.getObject("id", UUID.class), rs.getString("name"));
        });
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
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
