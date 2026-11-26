package com.company.yoga.identity.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class HealthProfileServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private final AccessPolicy access = mock(AccessPolicy.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final HealthProfileService service = new HealthProfileService(access, jdbc);
    private UserEntity user;

    private void signedInAs(String role) {
        user = new UserEntity();
        user.setId(ME);
        user.setDob(LocalDate.now().minusYears(30).minusDays(1));
        when(access.actor()).thenReturn(user);
        when(access.role(user)).thenReturn(role);
    }

    private static HealthProfileService.SaveReq req(List<String> conditions, List<String> pain, boolean pregnant, Integer week, boolean surgery) {
        return new HealthProfileService.SaveReq(LocalDate.of(1995, 5, 20), "FEMALE", new BigDecimal("160"), new BigDecimal("55"), "BEGINNER",
                "GENTLE", List.of("flexibility", "STRESS", "STRESS"), conditions, pain, "đau khi cúi", surgery, "mổ dây chằng", pregnant, week,
                false, "  ", "Phấn hoa", true, " Lan ", "0912345678", "Chị gái", null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void noProfileYetStillShowsAgeAndGender() {
        signedInAs("STUDENT");
        user.setGender("MALE");
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class))).thenReturn(List.of());
        var p = service.mine();
        assertThat(p.exists()).isFalse();
        assertThat(p.age()).isEqualTo(30);
        assertThat(p.gender()).isEqualTo("MALE");
        assertThat(p.goals()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void flagsAreDerivedAndDetailsKeptOnlyWhenTheyApply() {
        signedInAs("STUDENT");
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class))).thenReturn(List.of());

        service.save(req(List.of("HIGH_BP", "ASTHMA"), List.of(), false, 20, false));

        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        MapSqlParameterSource p = (MapSqlParameterSource) params.getValue();
        assertThat(p.getValue("heart")).isEqualTo(true);           // HIGH_BP → heart/blood-pressure flag
        assertThat(p.getValue("injuries")).isEqualTo(false);       // no pain area → no injury flag
        assertThat(p.getValue("injuryDetails")).isNull();
        assertThat(p.getValue("week")).isNull();                   // not pregnant
        assertThat(p.getValue("surgeryDetails")).isNull();         // no recent surgery
        assertThat(p.getValue("goals")).isEqualTo("FLEXIBILITY,STRESS");
        assertThat(p.getValue("medications")).isNull();
        assertThat(p.getValue("contactName")).isEqualTo("Lan");
        assertThat(user.getDob()).isEqualTo(LocalDate.of(1995, 5, 20));
        assertThat(user.getGender()).isEqualTo("FEMALE");
    }

    @SuppressWarnings("unchecked")
    @Test
    void painAreasMeanInjuries() {
        signedInAs("STUDENT");
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class))).thenReturn(List.of());
        service.save(req(List.of("DIABETES"), List.of("LOWER_BACK", "KNEE"), true, 20, true));
        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        MapSqlParameterSource p = (MapSqlParameterSource) params.getValue();
        assertThat(p.getValue("injuries")).isEqualTo(true);
        assertThat(p.getValue("heart")).isEqualTo(false);
        assertThat(p.getValue("injuryDetails")).isEqualTo("đau khi cúi");
        assertThat(p.getValue("week")).isEqualTo(20);
        assertThat(p.getValue("surgeryDetails")).isEqualTo("mổ dây chằng");
    }

    @Test
    void unknownCodesAndImpossibleBirthDatesAreRefused() {
        signedInAs("STUDENT");
        assertThatThrownBy(() -> service.save(req(List.of("CANCER"), List.of(), false, null, false))).hasMessageContaining("Bệnh nền");
        assertThatThrownBy(() -> service.save(req(List.of(), List.of("TOE"), false, null, false))).hasMessageContaining("Vùng đau");
        var tooYoung = new HealthProfileService.SaveReq(LocalDate.now().minusYears(2), null, null, null, null, null, null, null, null, null,
                false, null, false, null, false, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.save(tooYoung)).hasMessageContaining("Ngày sinh");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void onlyStudentsUseThisPage() {
        signedInAs("RECEPTIONIST");
        assertThatThrownBy(service::mine).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.save(req(List.of(), List.of(), false, null, false))).isInstanceOf(BusinessException.class);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }
}
