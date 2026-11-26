package com.company.yoga.ai.assistant;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A student's choice to let the assistant use three yes/no items of their health profile (injury, heart or blood
 * pressure, pregnancy) to pick videos. Off by default; the student can turn it off at any time. Free-text notes,
 * injury details and emergency contacts are never used.
 */
@Service
public class HealthConsentService {

    public record Consent(boolean consented, Instant updatedAt) {
    }

    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public HealthConsentService(AccessPolicy accessPolicy, NamedParameterJdbcTemplate jdbc) {
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
    }

    private UUID student() {
        UserEntity actor = accessPolicy.actor();
        if (!"STUDENT".equals(accessPolicy.role(actor))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return actor.getId();
    }

    public Consent mine() {
        return read(student());
    }

    public Consent set(boolean consented) {
        UUID me = student();
        jdbc.update("""
                INSERT INTO yoga.ai_health_consents AS c(student_id,consented,updated_at) VALUES(:me,:on,CURRENT_TIMESTAMP)
                ON CONFLICT (student_id) DO UPDATE SET consented=EXCLUDED.consented,updated_at=CURRENT_TIMESTAMP""",
                new MapSqlParameterSource("me", me).addValue("on", consented));
        return read(me);
    }

    /** Whether this student has consented right now (checked again before every health lookup). */
    public boolean consented(UUID studentId) {
        return read(studentId).consented();
    }

    private Consent read(UUID studentId) {
        List<Consent> rows = jdbc.query("SELECT consented,updated_at FROM yoga.ai_health_consents WHERE student_id=:me",
                Map.of("me", studentId), (rs, i) -> new Consent(rs.getBoolean("consented"), rs.getTimestamp("updated_at").toInstant()));
        return rows.isEmpty() ? new Consent(false, null) : rows.get(0);
    }
}
