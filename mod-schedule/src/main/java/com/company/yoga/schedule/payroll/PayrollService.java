package com.company.yoga.schedule.payroll;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instructor rate policies (with an online rate) and a monthly payroll preview priced by the database function
 * payroll_lines (V18). Nothing is booked: the preview is for checking and exporting.
 */
@Service
@RequiredArgsConstructor
public class PayrollService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AccessPolicy access;
    private final NamedParameterJdbcTemplate jdbc;

    public record SavePolicy(@NotNull UUID instructorId, UUID branchId,
                             @NotNull @DecimalMin("0") BigDecimal basePayPerClass,
                             @DecimalMin("0") BigDecimal onlinePayPerClass,
                             @DecimalMin("0") BigDecimal headcountBonusPerStudent,
                             LocalDate effectiveFrom) {}

    public record Policy(UUID id, UUID instructorId, String instructorName, UUID branchId, String branchName,
                         BigDecimal basePayPerClass, BigDecimal onlinePayPerClass, BigDecimal headcountBonusPerStudent,
                         Instant effectiveFrom, Instant effectiveTo, boolean active) {}

    public record Line(UUID scheduleId, Instant startTime, String deliveryMode, String className, UUID branchId,
                       int attended, int onlineAttended, BigDecimal sessionRate, BigDecimal bonusPerStudent, BigDecimal lineTotal) {}

    public record InstructorPay(UUID instructorId, String instructorName, int studioSessions, int onlineSessions, int hybridSessions,
                                int attended, int onlineAttended, int unpricedSessions, BigDecimal total, List<Line> lines) {}

    public record Preview(int year, int month, UUID branchId, List<InstructorPay> instructors, BigDecimal total, int unpricedSessions) {}

    // ---------------------------------------------------------------- policies

    @Transactional(readOnly = true)
    public List<Policy> policies(UUID instructorId) {
        UserEntity actor = access.actor();
        String role = access.role(actor);
        UUID onlyInstructor = instructorId;
        if ("INSTRUCTOR".equals(role)) onlyInstructor = actor.getId();
        else if (!"SUPER_ADMIN".equals(role) && !"BRANCH_MANAGER".equals(role)) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        var params = new MapSqlParameterSource().addValue("instructor", onlyInstructor).addValue("me", actor.getId())
                .addValue("manager", "BRANCH_MANAGER".equals(role));
        return jdbc.query("""
                SELECT p.*,u.full_name AS instructor_name,b.name AS branch_name FROM yoga.instructor_rate_policies p
                JOIN yoga.users u ON u.id=p.instructor_id LEFT JOIN yoga.branches b ON b.id=p.branch_id
                WHERE (CAST(:instructor AS uuid) IS NULL OR p.instructor_id=CAST(:instructor AS uuid))
                  AND (NOT :manager OR p.branch_id IS NULL OR p.branch_id IN (
                        SELECT home_branch_id FROM yoga.users WHERE id=:me UNION SELECT branch_id FROM yoga.user_branches WHERE user_id=:me))
                ORDER BY u.full_name, p.effective_from DESC""", params,
                (rs, i) -> new Policy(rs.getObject("id", UUID.class), rs.getObject("instructor_id", UUID.class), rs.getString("instructor_name"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"), rs.getBigDecimal("base_pay_per_class"),
                        rs.getBigDecimal("online_pay_per_class"), rs.getBigDecimal("headcount_bonus_per_student"),
                        instant(rs.getTimestamp("effective_from")), instant(rs.getTimestamp("effective_to")), rs.getBoolean("is_active")));
    }

    /** A new rate replaces the open policy of the same instructor and scope from its start date. */
    @Transactional
    public Policy createPolicy(SavePolicy req) {
        UserEntity actor = requireAdmin();
        LocalDate from = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now(VIETNAM);
        Instant start = from.atStartOfDay(VIETNAM).toInstant();
        var params = new MapSqlParameterSource().addValue("instructor", req.instructorId()).addValue("branch", req.branchId())
                .addValue("start", Timestamp.from(start)).addValue("base", req.basePayPerClass()).addValue("online", req.onlinePayPerClass())
                .addValue("bonus", req.headcountBonusPerStudent() == null ? BigDecimal.ZERO : req.headcountBonusPerStudent())
                .addValue("me", actor.getId());
        String sameScope = """
                instructor_id=:instructor AND is_active AND COALESCE(branch_id,'00000000-0000-0000-0000-000000000000'::uuid)
                  =COALESCE(CAST(:branch AS uuid),'00000000-0000-0000-0000-000000000000'::uuid)""";
        jdbc.query("SELECT id FROM yoga.users WHERE id=:instructor FOR UPDATE", params, rs -> null);
        Long later = jdbc.queryForObject("SELECT count(*) FROM yoga.instructor_rate_policies WHERE " + sameScope
                + " AND (effective_from>=:start OR (effective_to IS NOT NULL AND effective_to>:start))", params, Long.class);
        if (later != null && later > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "Đã có chính sách bắt đầu hoặc kết thúc sau ngày này. Hãy chọn ngày hiệu lực muộn hơn.");
        }
        jdbc.update("UPDATE yoga.instructor_rate_policies SET effective_to=:start WHERE " + sameScope + " AND effective_to IS NULL", params);
        try {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO yoga.instructor_rate_policies(instructor_id,branch_id,base_pay_per_class,online_pay_per_class,
                      headcount_bonus_per_student,effective_from,created_by)
                    VALUES(:instructor,:branch,:base,:online,:bonus,:start,:me) RETURNING id""", params, UUID.class);
            return policies(null).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        } catch (DataAccessException ex) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "Chính sách không hợp lệ: người được chọn phải là huấn luyện viên.");
        }
    }

    @Transactional
    public void deactivate(UUID id) {
        requireAdmin();
        if (jdbc.update("UPDATE yoga.instructor_rate_policies SET is_active=FALSE WHERE id=:id AND is_active", Map.of("id", id)) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    // ---------------------------------------------------------------- preview

    @Transactional(readOnly = true)
    public Preview preview(int year, int month, UUID branchId, UUID instructorId) {
        if (month < 1 || month > 12 || year < 2020 || year > 2100) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        UserEntity actor = access.actor();
        String role = access.role(actor);
        UUID branch = branchId;
        UUID instructor = instructorId;
        switch (role) {
            case "SUPER_ADMIN" -> { }
            case "BRANCH_MANAGER" -> {
                if (branch == null) branch = actor.getHomeBranchId();
                access.requireStaffBranch(branch);
            }
            case "INSTRUCTOR" -> { instructor = actor.getId(); branch = null; }
            default -> throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        LocalDate first = LocalDate.of(year, month, 1);
        var params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(first.atStartOfDay(VIETNAM).toInstant()))
                .addValue("to", Timestamp.from(first.plusMonths(1).atStartOfDay(VIETNAM).toInstant()))
                .addValue("branch", branch).addValue("instructor", instructor);
        record Row(UUID instructorId, String name, Line line) {}
        List<Row> rows = jdbc.query("""
                SELECT l.*,u.full_name FROM yoga.payroll_lines(:from,:to,CAST(:branch AS uuid),CAST(:instructor AS uuid)) l
                JOIN yoga.users u ON u.id=l.instructor_id ORDER BY u.full_name,l.start_time""", params,
                (rs, i) -> new Row(rs.getObject("instructor_id", UUID.class), rs.getString("full_name"),
                        new Line(rs.getObject("schedule_id", UUID.class), instant(rs.getTimestamp("start_time")), rs.getString("delivery_mode"),
                                rs.getString("class_name"), rs.getObject("branch_id", UUID.class), rs.getInt("attended"), rs.getInt("online_attended"),
                                rs.getBigDecimal("session_rate"), rs.getBigDecimal("bonus_per_student"), rs.getBigDecimal("line_total"))));
        Map<UUID, List<Row>> byInstructor = new LinkedHashMap<>();
        rows.forEach(r -> byInstructor.computeIfAbsent(r.instructorId(), k -> new ArrayList<>()).add(r));
        List<InstructorPay> pays = new ArrayList<>();
        BigDecimal grand = BigDecimal.ZERO;
        int unpricedAll = 0;
        for (var entry : byInstructor.entrySet()) {
            List<Line> lines = entry.getValue().stream().map(Row::line).toList();
            BigDecimal total = lines.stream().map(Line::lineTotal).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            int unpriced = (int) lines.stream().filter(l -> l.lineTotal() == null).count();
            pays.add(new InstructorPay(entry.getKey(), entry.getValue().get(0).name(),
                    count(lines, "IN_PERSON"), count(lines, "ONLINE"), count(lines, "HYBRID"),
                    lines.stream().mapToInt(Line::attended).sum(), lines.stream().mapToInt(Line::onlineAttended).sum(),
                    unpriced, total, lines));
            grand = grand.add(total);
            unpricedAll += unpriced;
        }
        return new Preview(year, month, branch, pays, grand, unpricedAll);
    }

    private static int count(List<Line> lines, String mode) {
        return (int) lines.stream().filter(l -> mode.equals(l.deliveryMode())).count();
    }

    private UserEntity requireAdmin() {
        UserEntity actor = access.actor();
        if (!"SUPER_ADMIN".equals(access.role(actor))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return actor;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
