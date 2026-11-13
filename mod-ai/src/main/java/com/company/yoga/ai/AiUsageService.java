package com.company.yoga.ai;

import com.company.yoga.common.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-user daily quotas and the system-wide daily budget, both on Vietnam dates. The checks run before calling the
 * provider and are not atomic with the call: a few parallel requests may pass the limit by one call each, which is
 * acceptable for a cost guard.
 */
@Service
@Slf4j
public class AiUsageService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final NamedParameterJdbcTemplate jdbc;
    private final AiProperties properties;
    private final Clock clock;

    @Autowired
    public AiUsageService(NamedParameterJdbcTemplate jdbc, AiProperties properties) {
        this(jdbc, properties, Clock.system(VIETNAM));
    }

    AiUsageService(NamedParameterJdbcTemplate jdbc, AiProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(VIETNAM));
    }

    public void checkQuota(UUID userId, boolean staff) {
        var params = new MapSqlParameterSource("user", userId).addValue("day", today());
        Integer used = jdbc.query("SELECT requests FROM yoga.ai_usage_daily WHERE user_id=:user AND usage_date=:day", params,
                rs -> rs.next() ? rs.getInt(1) : 0);
        int limit = staff ? properties.quota().staffRequestsPerDay() : properties.quota().studentRequestsPerDay();
        if (used != null && used >= limit) throw new BusinessException(AiResultCodes.QUOTA_EXCEEDED);

        long budget = micro(properties.quota().dailyBudgetUsd());
        if (budget > 0 && spentToday() >= budget) {
            log.warn("AI daily budget of {} USD reached; AI paused until midnight", properties.quota().dailyBudgetUsd());
            throw new BusinessException(AiResultCodes.BUDGET_EXCEEDED);
        }
    }

    long spentToday() {
        Long spent = jdbc.queryForObject("SELECT COALESCE(sum(cost_micro_usd),0) FROM yoga.ai_usage_daily WHERE usage_date=:day",
                new MapSqlParameterSource("day", today()), Long.class);
        return spent == null ? 0 : spent;
    }

    /** Adds one request to today's counters and returns its cost in micro USD. */
    public long record(UUID userId, AiProperties.Feature feature, AiProvider.Usage usage) {
        long cost = cost(feature, usage);
        jdbc.update("""
                INSERT INTO yoga.ai_usage_daily AS u(user_id,usage_date,requests,input_tokens,cached_tokens,output_tokens,cost_micro_usd)
                VALUES(:user,:day,1,:input,:cached,:output,:cost)
                ON CONFLICT (user_id,usage_date) DO UPDATE SET requests=u.requests+1,
                  input_tokens=u.input_tokens+EXCLUDED.input_tokens, cached_tokens=u.cached_tokens+EXCLUDED.cached_tokens,
                  output_tokens=u.output_tokens+EXCLUDED.output_tokens, cost_micro_usd=u.cost_micro_usd+EXCLUDED.cost_micro_usd""",
                new MapSqlParameterSource("user", userId).addValue("day", today()).addValue("input", usage.inputTokens())
                        .addValue("cached", usage.cachedTokens()).addValue("output", usage.outputTokens()).addValue("cost", cost));
        warnNearBudget(cost);
        return cost;
    }

    private void warnNearBudget(long cost) {
        long budget = micro(properties.quota().dailyBudgetUsd());
        if (budget <= 0 || cost <= 0) return;
        long alert = budget * properties.quota().alertPercent() / 100;
        long spent = spentToday();
        if (spent >= alert && spent - cost < alert) {
            log.warn("AI spending reached {}% of the daily budget ({} of {} micro USD)", properties.quota().alertPercent(), spent, budget);
        }
    }

    /**
     * Cost in micro USD: uncached input at the input price, cached input at the cached price (input price when no
     * cached price is configured), output (including thinking) at the output price.
     */
    static long cost(AiProperties.Feature feature, AiProvider.Usage usage) {
        BigDecimal in = orZero(feature.priceInput());
        BigDecimal cached = feature.priceCachedInput() == null ? in : feature.priceCachedInput();
        long cachedTokens = Math.min(usage.cachedTokens(), usage.inputTokens());
        BigDecimal usd = in.multiply(BigDecimal.valueOf(usage.inputTokens() - cachedTokens))
                .add(cached.multiply(BigDecimal.valueOf(cachedTokens)))
                .add(orZero(feature.priceOutput()).multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(MILLION);
        return micro(usd);
    }

    private static long micro(BigDecimal usd) {
        return usd == null ? 0 : usd.multiply(MILLION).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
