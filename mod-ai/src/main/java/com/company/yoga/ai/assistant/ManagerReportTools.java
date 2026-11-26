package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProvider.ToolSpec;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.report.OnlineReportService;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.revenue.dto.RevenueReportDto;
import com.company.yoga.membership.revenue.service.RevenueReportService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only report functions for the manager assistant. Revenue and online figures come from the same services as
 * the report pages (same permission and branch rules); branches and class occupancy are read here with the same
 * branch rules: a super admin sees every branch, a branch manager only their own.
 */
@Component
public class ManagerReportTools {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_DAYS = 366;
    static final int DAILY_TREND_MAX_DAYS = 31;

    private final RevenueReportService revenue;
    private final OnlineReportService online;
    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public ManagerReportTools(RevenueReportService revenue, OnlineReportService online, AccessPolicy accessPolicy,
                              NamedParameterJdbcTemplate jdbc) {
        this.revenue = revenue;
        this.online = online;
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
    }

    public List<AiTool> tools() {
        return List.of(listBranches(), revenueReport(), onlineReport(), classOccupancy());
    }

    // ---------------------------------------------------------------- tools

    private static Map<String, Object> periodParams(boolean withPaymentMethod) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("startDate", Map.of("type", "string", "description", "Ngày bắt đầu, dạng YYYY-MM-DD (giờ Việt Nam)"));
        props.put("endDate", Map.of("type", "string", "description", "Ngày kết thúc, dạng YYYY-MM-DD, tính cả ngày này"));
        props.put("branchId", Map.of("type", "string", "description",
                "ID chi nhánh lấy từ list_branches. Bỏ trống: chủ chuỗi xem cả chuỗi, quản lý chi nhánh xem chi nhánh chính của mình"));
        if (withPaymentMethod) {
            props.put("paymentMethod", Map.of("type", "string", "description", "Lọc phương thức thanh toán; bỏ trống là tất cả",
                    "enum", List.of("CASH", "POS_CARD", "BANK_TRANSFER_QR", "PAYOS", "VNPAY")));
        }
        return Map.of("type", "object", "properties", props, "required", List.of("startDate", "endDate"));
    }

    AiTool listBranches() {
        return tool(new ToolSpec("list_branches",
                "Danh sách chi nhánh người hỏi được xem (id, mã, tên). Gọi khi người hỏi nhắc tên chi nhánh để lấy branchId.", null),
                args -> {
                    Scope scope = scope();
                    String sql = "SELECT b.id,b.code,b.name FROM yoga.branches b WHERE b.is_active"
                            + (scope.superAdmin() ? "" : " AND (b.id=:home OR EXISTS(SELECT 1 FROM yoga.user_branches ub WHERE ub.branch_id=b.id AND ub.user_id=:me))")
                            + " ORDER BY b.name";
                    var rows = jdbc.query(sql, new MapSqlParameterSource("me", scope.userId()).addValue("home", scope.homeBranchId()),
                            (rs, i) -> row("id", rs.getObject("id", UUID.class).toString(), "code", rs.getString("code"), "name", rs.getString("name")));
                    return Map.of("branches", rows);
                });
    }

    AiTool revenueReport() {
        return tool(new ToolSpec("get_revenue_report",
                "Doanh thu thực thu (VNĐ) trong một khoảng ngày: tổng, số đơn, giá trị đơn trung bình, số thẻ kích hoạt, theo chi nhánh, "
                        + "theo gói thẻ, theo phương thức thanh toán và theo ngày (khoảng <= 31 ngày) hoặc theo tháng.",
                periodParams(true)),
                args -> {
                    Period period = period(args);
                    RevenueReportDto.ReportResponse r = revenue.getRevenueReport(period.start(), period.end(),
                            uuid(args.get("branchId")), string(args.get("paymentMethod")));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("from", r.fromDate().toString());
                    out.put("to", r.toDate().toString());
                    out.put("branch", r.filteredBranchName() == null ? "Toàn chuỗi" : r.filteredBranchName());
                    var o = r.overview();
                    out.put("totalRevenue", o.totalRevenue());
                    out.put("orders", o.totalOrders());
                    out.put("averageOrderValue", o.averageOrderValue());
                    out.put("membershipsActivated", o.totalMembershipsActivated());
                    out.put("byBranch", r.branchSummaries().stream().map(b -> row("branch", b.branchName(), "revenue", b.totalRevenue(),
                            "orders", b.orderCount(), "percent", round(b.percentage()))).toList());
                    out.put("topPlans", r.planSummaries().stream().limit(10).map(p -> row("plan", p.planName(), "sold", p.quantitySold(),
                            "revenue", p.totalRevenue(), "percent", round(p.percentage()))).toList());
                    out.put("byPaymentMethod", r.paymentMethodSummaries().stream().map(p -> row("method", p.methodName(),
                            "amount", p.totalAmount(), "transactions", p.transactionCount(), "percent", round(p.percentage()))).toList());
                    out.put(period.days() <= DAILY_TREND_MAX_DAYS ? "byDay" : "byMonth", trend(r.dailyTrends(), period.days()));
                    return out;
                });
    }

    AiTool onlineReport() {
        return tool(new ToolSpec("get_online_report",
                "Hoạt động online trong một khoảng ngày: số buổi lớp online/hybrid, lượt đặt và lượt vào lớp online, tỷ lệ vào lớp, "
                        + "học viên tham gia, doanh thu gói online và khóa online, số thẻ online còn hạn, lượt xem video và video xem nhiều.",
                periodParams(false)),
                args -> {
                    Period period = period(args);
                    OnlineReportService.Report r = online.report(period.start(), period.end(), uuid(args.get("branchId")));
                    var k = r.kpi();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("from", r.startDate().toString());
                    out.put("to", r.endDate().toString());
                    out.put("sessions", k.sessions());
                    out.put("heldSessions", k.heldSessions());
                    out.put("onlineBookings", k.bookings());
                    out.put("onlineJoins", k.joins());
                    out.put("attendanceRatePercent", k.attendanceRate());
                    out.put("uniqueStudents", k.uniqueStudents());
                    out.put("onlinePackageRevenue", k.packageRevenue());
                    out.put("onlineCourseRevenue", k.courseRevenue());
                    out.put("activeOnlinePasses", k.activeOnlinePasses());
                    out.put("videoPlays", k.videoPlays());
                    out.put("videoViewers", k.videoViewers());
                    out.put("videoCompletions", k.videoCompletions());
                    out.put("videoHours", k.videoHours());
                    out.put("byBranch", r.branches().stream().map(b -> row("branch", b.branchName(), "sessions", b.sessions(),
                            "bookings", b.bookings(), "joins", b.joins(), "attendanceRatePercent", b.attendanceRate())).toList());
                    out.put("plans", r.plans().stream().map(p -> row("plan", p.planName(), "scope", p.accessScope(),
                            "sold", p.sold(), "revenue", p.revenue())).toList());
                    out.put("topVideos", r.topVideos().stream().limit(10).map(v -> row("title", v.title(), "plays", v.plays(),
                            "viewers", v.viewers(), "completions", v.completions())).toList());
                    out.put("note", "Thư viện video dùng chung toàn chuỗi nên số liệu video không lọc theo chi nhánh");
                    return out;
                });
    }

    AiTool classOccupancy() {
        return tool(new ToolSpec("get_class_occupancy",
                "Sĩ số các buổi lớp đã diễn ra (tại phòng và online) trong một khoảng ngày: số buổi, chỗ đã đặt so với sức chứa, "
                        + "số có mặt và vắng không báo, theo bộ môn và theo chi nhánh; 10 buổi lấp đầy thấp nhất; số buổi đã hủy.",
                periodParams(false)),
                args -> {
                    Period period = period(args);
                    UUID branch = branchScope(uuid(args.get("branchId")));
                    var p = new MapSqlParameterSource().addValue("from", Timestamp.from(period.from())).addValue("to", Timestamp.from(period.to()))
                            .addValue("branch", branch);
                    String held = """
                            FROM yoga.class_schedules s JOIN yoga.class_types ct ON ct.id=s.class_type_id JOIN yoga.branches br ON br.id=s.branch_id
                            WHERE s.status<>'CANCELLED' AND s.start_time>=:from AND s.start_time<:to AND s.start_time<CURRENT_TIMESTAMP
                              AND (CAST(:branch AS uuid) IS NULL OR s.branch_id=CAST(:branch AS uuid))""";
                    String counts = """
                            count(*) AS sessions, COALESCE(sum(s.max_capacity),0) AS capacity, COALESCE(sum(s.booked_count),0) AS booked,
                            COALESCE(sum((SELECT count(*) FROM yoga.bookings b WHERE b.schedule_id=s.id AND b.status='ATTENDED')),0) AS attended,
                            COALESCE(sum((SELECT count(*) FROM yoga.bookings b WHERE b.schedule_id=s.id AND b.status='NO_SHOW')),0) AS no_show""";
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("from", period.start().toString());
                    out.put("to", period.end().toString());
                    out.put("total", jdbc.queryForObject("SELECT " + counts + " " + held, p, (rs, i) -> occupancy(rs, null)));
                    out.put("byClassType", jdbc.query("SELECT ct.name AS label, " + counts + " " + held
                            + " GROUP BY ct.name ORDER BY count(*) DESC LIMIT 20", p, (rs, i) -> occupancy(rs, rs.getString("label"))));
                    out.put("byBranch", jdbc.query("SELECT br.name AS label, " + counts + " " + held
                            + " GROUP BY br.name ORDER BY count(*) DESC", p, (rs, i) -> occupancy(rs, rs.getString("label"))));
                    out.put("lowestFill", jdbc.query("""
                            SELECT ct.name AS class_type, br.name AS branch, s.delivery_mode,
                              to_char(s.start_time AT TIME ZONE 'Asia/Ho_Chi_Minh','DD/MM/YYYY HH24:MI') AS start_at,
                              s.booked_count, s.max_capacity """ + held + """
                             ORDER BY s.booked_count::numeric/s.max_capacity, s.start_time LIMIT 10""", p,
                            (rs, i) -> row("classType", rs.getString("class_type"), "branch", rs.getString("branch"),
                                    "mode", rs.getString("delivery_mode"), "startAt", rs.getString("start_at"),
                                    "booked", rs.getInt("booked_count"), "capacity", rs.getInt("max_capacity"))));
                    out.put("cancelledSessions", jdbc.queryForObject("""
                            SELECT count(*) FROM yoga.class_schedules s WHERE s.status='CANCELLED' AND s.start_time>=:from AND s.start_time<:to
                              AND (CAST(:branch AS uuid) IS NULL OR s.branch_id=CAST(:branch AS uuid))""", p, Long.class));
                    return out;
                });
    }

    private static Map<String, Object> occupancy(java.sql.ResultSet rs, String label) throws java.sql.SQLException {
        long capacity = rs.getLong("capacity");
        long booked = rs.getLong("booked");
        Map<String, Object> m = new LinkedHashMap<>();
        if (label != null) m.put("label", label);
        m.put("sessions", rs.getLong("sessions"));
        m.put("capacity", capacity);
        m.put("booked", booked);
        m.put("fillPercent", capacity == 0 ? 0 : round(100.0 * booked / capacity));
        m.put("attended", rs.getLong("attended"));
        m.put("noShow", rs.getLong("no_show"));
        return m;
    }

    // ---------------------------------------------------------------- helpers

    record Scope(UUID userId, UUID homeBranchId, boolean superAdmin) {
    }

    /** Only chain owners and branch managers use the assistant. */
    Scope scope() {
        UserEntity actor = accessPolicy.actor();
        String role = accessPolicy.role(actor);
        if (!"SUPER_ADMIN".equals(role) && !"BRANCH_MANAGER".equals(role)) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return new Scope(actor.getId(), actor.getHomeBranchId(), "SUPER_ADMIN".equals(role));
    }

    /** Same rule as the report pages: a branch manager is limited to their branches, defaulting to the main one. */
    UUID branchScope(UUID requested) {
        Scope scope = scope();
        if (scope.superAdmin()) return requested;
        if (requested != null) {
            accessPolicy.requireStaffBranch(requested);
            return requested;
        }
        if (scope.homeBranchId() != null) return scope.homeBranchId();
        return jdbc.queryForList("SELECT branch_id FROM yoga.user_branches WHERE user_id=:me ORDER BY branch_id LIMIT 1",
                        Map.of("me", scope.userId()), UUID.class).stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    record Period(LocalDate start, LocalDate end) {
        long days() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }

        Instant from() {
            return start.atStartOfDay(VIETNAM).toInstant();
        }

        Instant to() {
            return end.plusDays(1).atStartOfDay(VIETNAM).toInstant();
        }
    }

    static Period period(Map<String, Object> args) {
        LocalDate start = date(args.get("startDate"), "startDate");
        LocalDate end = date(args.get("endDate"), "endDate");
        if (start.isAfter(end)) {
            LocalDate t = start;
            start = end;
            end = t;
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("Khoảng thời gian tối đa " + MAX_DAYS + " ngày; hãy chia nhỏ khoảng ngày");
        }
        return new Period(start, end);
    }

    private static LocalDate date(Object value, String name) {
        String s = string(value);
        if (s == null) throw new IllegalArgumentException("Thiếu " + name + " (YYYY-MM-DD)");
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " phải có dạng YYYY-MM-DD");
        }
    }

    static UUID uuid(Object value) {
        String s = string(value);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("branchId không hợp lệ; hãy lấy id từ list_branches");
        }
    }

    static String string(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Daily rows for short periods, monthly totals for long ones, to keep the answer small. */
    static List<Map<String, Object>> trend(List<RevenueReportDto.DailyTrend> daily, long days) {
        if (days <= DAILY_TREND_MAX_DAYS) {
            return daily.stream().map(d -> row("date", d.date(), "revenue", d.amount(), "orders", d.orderCount())).toList();
        }
        Map<String, BigDecimal> revenue = new TreeMap<>();
        Map<String, Long> orders = new TreeMap<>();
        for (RevenueReportDto.DailyTrend d : daily) {
            String month = d.date().substring(0, 7);
            revenue.merge(month, d.amount() == null ? BigDecimal.ZERO : d.amount(), BigDecimal::add);
            orders.merge(month, d.orderCount(), Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        revenue.forEach((month, amount) -> out.add(row("month", month, "revenue", amount, "orders", orders.get(month))));
        return out;
    }

    static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static AiTool tool(ToolSpec spec, java.util.function.Function<Map<String, Object>, Map<String, Object>> body) {
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
