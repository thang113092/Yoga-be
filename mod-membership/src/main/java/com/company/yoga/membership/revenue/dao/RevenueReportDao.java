package com.company.yoga.membership.revenue.dao;

import com.company.yoga.membership.revenue.dto.RevenueReportDto;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RevenueReportDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private MapSqlParameterSource createParams(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod
    ) {
        return new MapSqlParameterSource()
                .addValue("fromTime", java.sql.Timestamp.from(fromTime), Types.TIMESTAMP)
                .addValue("toTime", java.sql.Timestamp.from(toTime), Types.TIMESTAMP)
                .addValue("branchId", branchId, Types.OTHER)
                .addValue("paymentMethod", paymentMethod, Types.VARCHAR);
    }

    public RevenueReportDto.KpiOverview getKpiOverview(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod
    ) {
        MapSqlParameterSource params = createParams(fromTime, toTime, branchId, paymentMethod);

        String sqlRev = """
                SELECT
                    COALESCE(SUM(p.amount), 0) AS total_revenue,
                    COUNT(DISTINCT p.order_id) AS total_orders
                FROM yoga.payments p
                WHERE p.payment_status = 'SUCCESS'
                  AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                  AND (CAST(:branchId AS uuid) IS NULL OR p.branch_id = CAST(:branchId AS uuid))
                  AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                """;

        String sqlMemberships = """
                SELECT COUNT(DISTINCT m.id) AS total_memberships
                FROM yoga.memberships m
                JOIN yoga.order_items oi ON m.source_order_item_id = oi.id
                JOIN yoga.orders o ON oi.order_id = o.id
                JOIN yoga.payments p ON p.order_id = o.id
                WHERE p.payment_status = 'SUCCESS'
                  AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                  AND (CAST(:branchId AS uuid) IS NULL OR p.branch_id = CAST(:branchId AS uuid))
                  AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                """;

        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalOrders = 0;

        List<Object[]> revResult = jdbcTemplate.query(sqlRev, params, (rs, rowNum) ->
                new Object[]{rs.getBigDecimal("total_revenue"), rs.getLong("total_orders")});

        if (!revResult.isEmpty()) {
            totalRevenue = (BigDecimal) revResult.get(0)[0];
            totalOrders = (Long) revResult.get(0)[1];
        }

        Long totalMemberships = jdbcTemplate.queryForObject(sqlMemberships, params, Long.class);
        long membershipsActivated = totalMemberships != null ? totalMemberships : 0L;

        BigDecimal aov = totalOrders > 0
                ? totalRevenue.divide(BigDecimal.valueOf(totalOrders), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new RevenueReportDto.KpiOverview(totalRevenue, totalOrders, aov, membershipsActivated);
    }

    public List<RevenueReportDto.DailyTrend> getDailyTrends(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod
    ) {
        MapSqlParameterSource params = createParams(fromTime, toTime, branchId, paymentMethod);

        String sql = """
                SELECT
                    to_char(p.payment_time AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD') AS report_date,
                    COALESCE(SUM(p.amount), 0) AS daily_revenue,
                    COUNT(DISTINCT p.order_id) AS order_count
                FROM yoga.payments p
                WHERE p.payment_status = 'SUCCESS'
                  AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                  AND (CAST(:branchId AS uuid) IS NULL OR p.branch_id = CAST(:branchId AS uuid))
                  AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                GROUP BY to_char(p.payment_time AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD')
                ORDER BY report_date ASC
                """;

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new RevenueReportDto.DailyTrend(
                rs.getString("report_date"),
                rs.getBigDecimal("daily_revenue"),
                rs.getLong("order_count")
        ));
    }

    public List<RevenueReportDto.PaymentMethodSummary> getPaymentMethodSummaries(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod,
            BigDecimal overallRevenue
    ) {
        MapSqlParameterSource params = createParams(fromTime, toTime, branchId, paymentMethod);

        String sql = """
                SELECT
                    p.payment_method,
                    COALESCE(SUM(p.amount), 0) AS total_amount,
                    COUNT(*) AS transaction_count
                FROM yoga.payments p
                WHERE p.payment_status = 'SUCCESS'
                  AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                  AND (CAST(:branchId AS uuid) IS NULL OR p.branch_id = CAST(:branchId AS uuid))
                  AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                GROUP BY p.payment_method
                ORDER BY total_amount DESC
                """;

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            String method = rs.getString("payment_method");
            BigDecimal total = rs.getBigDecimal("total_amount");
            long count = rs.getLong("transaction_count");
            double pct = overallRevenue.compareTo(BigDecimal.ZERO) > 0
                    ? total.multiply(BigDecimal.valueOf(100)).divide(overallRevenue, 2, java.math.RoundingMode.HALF_UP).doubleValue()
                    : 0.0;

            String name = switch (method) {
                case "CASH" -> "Tiền mặt tại quầy";
                case "POS_CARD" -> "Quẹt thẻ POS ngân hàng";
                case "BANK_TRANSFER_QR" -> "Chuyển khoản QR Napas";
                default -> method;
            };

            return new RevenueReportDto.PaymentMethodSummary(method, name, total, count, pct);
        });
    }

    public List<RevenueReportDto.PlanRevenueSummary> getPlanRevenueSummaries(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod,
            BigDecimal overallRevenue
    ) {
        MapSqlParameterSource params = createParams(fromTime, toTime, branchId, paymentMethod);

        String sql = """
                SELECT
                    oi.item_id AS plan_id,
                    oi.item_name_snapshot AS plan_name,
                    COALESCE(SUM(oi.quantity), 0) AS quantity_sold,
                    COALESCE(SUM(oi.line_total), 0) AS total_revenue
                FROM yoga.order_items oi
                JOIN yoga.orders o ON oi.order_id = o.id
                JOIN yoga.payments p ON p.order_id = o.id
                WHERE p.payment_status = 'SUCCESS'
                  AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                  AND (CAST(:branchId AS uuid) IS NULL OR p.branch_id = CAST(:branchId AS uuid))
                  AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                GROUP BY oi.item_id, oi.item_name_snapshot
                ORDER BY total_revenue DESC
                LIMIT 15
                """;

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            UUID planId = rs.getObject("plan_id", UUID.class);
            String planName = rs.getString("plan_name");
            long qty = rs.getLong("quantity_sold");
            BigDecimal rev = rs.getBigDecimal("total_revenue");
            double pct = overallRevenue.compareTo(BigDecimal.ZERO) > 0
                    ? rev.multiply(BigDecimal.valueOf(100)).divide(overallRevenue, 2, java.math.RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            return new RevenueReportDto.PlanRevenueSummary(planId, planName, qty, rev, pct);
        });
    }

    public List<RevenueReportDto.BranchRevenueSummary> getBranchRevenueSummaries(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod,
            BigDecimal overallRevenue
    ) {
        MapSqlParameterSource params = createParams(fromTime, toTime, branchId, paymentMethod);

        String sql = """
                SELECT
                    b.id AS branch_id,
                    b.code AS branch_code,
                    b.name AS branch_name,
                    COALESCE(SUM(p.amount), 0) AS total_revenue,
                    COUNT(DISTINCT p.order_id) AS order_count
                FROM yoga.branches b
                LEFT JOIN yoga.payments p ON p.branch_id = b.id
                    AND p.payment_status = 'SUCCESS'
                    AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                    AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                WHERE (CAST(:branchId AS uuid) IS NULL OR b.id = CAST(:branchId AS uuid))
                GROUP BY b.id, b.code, b.name
                ORDER BY total_revenue DESC
                """;

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            UUID id = rs.getObject("branch_id", UUID.class);
            String code = rs.getString("branch_code");
            String name = rs.getString("branch_name");
            BigDecimal rev = rs.getBigDecimal("total_revenue");
            long orderCount = rs.getLong("order_count");
            double pct = overallRevenue.compareTo(BigDecimal.ZERO) > 0
                    ? rev.multiply(BigDecimal.valueOf(100)).divide(overallRevenue, 2, java.math.RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            return new RevenueReportDto.BranchRevenueSummary(id, code, name, rev, orderCount, pct);
        });
    }

    public List<RevenueReportDto.RecentTransaction> getRecentTransactions(
            Instant fromTime,
            Instant toTime,
            UUID branchId,
            String paymentMethod,
            int limit
    ) {
        MapSqlParameterSource params = createParams(fromTime, toTime, branchId, paymentMethod)
                .addValue("limit", limit, Types.INTEGER);

        String sql = """
                SELECT
                    o.id AS order_id,
                    o.order_code,
                    p.id AS payment_id,
                    p.payment_code,
                    p.payment_time,
                    o.branch_id,
                    b.name AS branch_name,
                    u_cust.full_name AS customer_name,
                    u_cust.phone AS customer_phone,
                    COALESCE(oi.item_name_snapshot, 'Gói tập Yoga') AS plan_name,
                    p.amount,
                    p.payment_method,
                    u_cashier.full_name AS cashier_name,
                    p.payment_status AS status
                FROM yoga.payments p
                JOIN yoga.orders o ON p.order_id = o.id
                LEFT JOIN yoga.branches b ON p.branch_id = b.id
                LEFT JOIN yoga.users u_cust ON o.customer_id = u_cust.id
                LEFT JOIN yoga.users u_cashier ON p.cashier_id = u_cashier.id
                LEFT JOIN LATERAL (
                    SELECT item_name_snapshot FROM yoga.order_items WHERE order_id = o.id LIMIT 1
                ) oi ON TRUE
                WHERE p.payment_status = 'SUCCESS'
                  AND p.payment_time >= :fromTime AND p.payment_time < :toTime
                  AND (CAST(:branchId AS uuid) IS NULL OR p.branch_id = CAST(:branchId AS uuid))
                  AND (CAST(:paymentMethod AS text) IS NULL OR p.payment_method = CAST(:paymentMethod AS text))
                ORDER BY p.payment_time DESC
                LIMIT :limit
                """;

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            String method = rs.getString("payment_method");
            String methodName = switch (method) {
                case "CASH" -> "Tiền mặt";
                case "POS_CARD" -> "Quẹt thẻ POS";
                case "BANK_TRANSFER_QR" -> "Chuyển khoản QR";
                default -> method;
            };

            return new RevenueReportDto.RecentTransaction(
                    rs.getObject("order_id", UUID.class),
                    rs.getString("order_code"),
                    rs.getObject("payment_id", UUID.class),
                    rs.getString("payment_code"),
                    rs.getTimestamp("payment_time").toInstant(),
                    rs.getObject("branch_id", UUID.class),
                    rs.getString("branch_name"),
                    rs.getString("customer_name"),
                    rs.getString("customer_phone"),
                    rs.getString("plan_name"),
                    rs.getBigDecimal("amount"),
                    method,
                    methodName,
                    rs.getString("cashier_name"),
                    rs.getString("status")
            );
        });
    }
}
