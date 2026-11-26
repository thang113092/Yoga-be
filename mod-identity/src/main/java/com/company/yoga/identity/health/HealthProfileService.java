package com.company.yoga.identity.health;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.service.AccessPolicy;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A student's own health profile ({@code student_medical_profiles}, plus birth date and gender on {@code users}): only
 * the student reads and edits it here. The three V1 yes/no flags are derived (injuries from pain areas, heart/blood
 * pressure from conditions) and are all that video warnings and the AI filter read; free text, medications, body
 * measurements and the emergency contact never leave the system.
 */
@Service
public class HealthProfileService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Set<String> GOALS = Set.of("FLEXIBILITY", "STRENGTH", "STRESS", "BACK_PAIN", "POSTURE", "WEIGHT", "BREATHING", "SLEEP",
            "REHAB", "PRENATAL");
    static final Set<String> CONDITIONS = Set.of("HIGH_BP", "LOW_BP", "HEART", "DIABETES", "ASTHMA", "OSTEOPOROSIS", "HERNIATED_DISC",
            "SCOLIOSIS", "ARTHRITIS", "EPILEPSY", "VERTIGO", "GLAUCOMA");
    static final Set<String> HEART_OR_BP = Set.of("HIGH_BP", "LOW_BP", "HEART");
    static final Set<String> PAIN_AREAS = Set.of("NECK", "SHOULDER", "UPPER_BACK", "LOWER_BACK", "HIP", "KNEE", "WRIST", "ANKLE", "OTHER");

    public record Profile(
            boolean exists,
            // about you (users table)
            LocalDate dob, Integer age, String gender,
            // body and practice
            BigDecimal heightCm, BigDecimal weightKg, String experienceLevel, String preferredIntensity, List<String> goals,
            // health
            List<String> conditions, List<String> painAreas, String injuryDetails, boolean recentSurgery, String surgeryDetails,
            boolean isPregnant, Integer pregnancyWeek, boolean postpartum, String medications, String allergies, Boolean doctorClearance,
            // derived flags (what video warnings and the AI read)
            boolean hasInjuries, boolean hasHeartOrBp,
            // emergency and notes
            String emergencyContactName, String emergencyContactPhone, String emergencyContactRelation, String medicalNotes,
            Instant updatedAt) {
    }

    public record SaveReq(
            @Past(message = "Ngày sinh không hợp lệ") LocalDate dob,
            @Pattern(regexp = "^$|MALE|FEMALE|OTHER", message = "Giới tính không hợp lệ") String gender,
            @DecimalMin(value = "50", message = "Chiều cao từ 50 đến 250 cm") @DecimalMax(value = "250", message = "Chiều cao từ 50 đến 250 cm") BigDecimal heightCm,
            @DecimalMin(value = "20", message = "Cân nặng từ 20 đến 300 kg") @DecimalMax(value = "300", message = "Cân nặng từ 20 đến 300 kg") BigDecimal weightKg,
            @Pattern(regexp = "^$|NEW|BEGINNER|INTERMEDIATE|ADVANCED", message = "Kinh nghiệm không hợp lệ") String experienceLevel,
            @Pattern(regexp = "^$|GENTLE|MODERATE|STRONG", message = "Cường độ không hợp lệ") String preferredIntensity,
            List<String> goals,
            List<String> conditions,
            List<String> painAreas,
            @Size(max = 500, message = "Mô tả chấn thương tối đa 500 ký tự") String injuryDetails,
            @NotNull Boolean recentSurgery,
            @Size(max = 500, message = "Mô tả phẫu thuật tối đa 500 ký tự") String surgeryDetails,
            @NotNull Boolean isPregnant,
            @Min(value = 1, message = "Tuần thai từ 1 đến 45") @Max(value = 45, message = "Tuần thai từ 1 đến 45") Integer pregnancyWeek,
            @NotNull Boolean postpartum,
            @Size(max = 500, message = "Thuốc đang dùng tối đa 500 ký tự") String medications,
            @Size(max = 300, message = "Dị ứng tối đa 300 ký tự") String allergies,
            Boolean doctorClearance,
            @Size(max = 100, message = "Tên người liên hệ tối đa 100 ký tự") String emergencyContactName,
            @Pattern(regexp = "^$|^\\+?[0-9 .-]{8,20}$", message = "Số điện thoại liên hệ không hợp lệ") String emergencyContactPhone,
            @Size(max = 50, message = "Mối quan hệ tối đa 50 ký tự") String emergencyContactRelation,
            @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự") String medicalNotes) {
    }

    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;

    public HealthProfileService(AccessPolicy accessPolicy, NamedParameterJdbcTemplate jdbc) {
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
    }

    private UserEntity student() {
        UserEntity actor = accessPolicy.actor();
        if (!"STUDENT".equals(accessPolicy.role(actor))) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        return actor;
    }

    @Transactional(readOnly = true)
    public Profile mine() {
        UserEntity me = student();
        LocalDate dob = me.getDob();
        Integer age = dob == null ? null : Period.between(dob, LocalDate.now(VIETNAM)).getYears();
        List<Profile> rows = jdbc.query("""
                SELECT * FROM yoga.student_medical_profiles WHERE student_id=:me""", Map.of("me", me.getId()),
                (rs, i) -> new Profile(true, dob, age, me.getGender(),
                        rs.getBigDecimal("height_cm"), rs.getBigDecimal("weight_kg"), rs.getString("experience_level"),
                        rs.getString("preferred_intensity"), list(rs.getArray("goals")),
                        list(rs.getArray("conditions")), list(rs.getArray("pain_areas")), rs.getString("injury_details"),
                        rs.getBoolean("recent_surgery"), rs.getString("surgery_details"),
                        rs.getBoolean("is_pregnant"), (Integer) rs.getObject("pregnancy_week"), rs.getBoolean("postpartum"),
                        rs.getString("medications"), rs.getString("allergies"), (Boolean) rs.getObject("doctor_clearance"),
                        rs.getBoolean("has_injuries"), rs.getBoolean("has_heart_or_bp"),
                        rs.getString("emergency_contact_name"), rs.getString("emergency_contact_phone"),
                        rs.getString("emergency_contact_relation"), rs.getString("medical_notes"), rs.getTimestamp("updated_at").toInstant()));
        if (!rows.isEmpty()) return rows.get(0);
        return new Profile(false, dob, age, me.getGender(), null, null, null, null, List.of(), List.of(), List.of(), null, false, null,
                false, null, false, null, null, null, false, false, null, null, null, null, null);
    }

    /** Details are only kept when they apply (no pain → no injury details, not pregnant → no week, no surgery → no details). */
    @Transactional
    public Profile save(SaveReq req) {
        UserEntity me = student();
        List<String> goals = codes(req.goals(), GOALS, "Mục tiêu");
        List<String> conditions = codes(req.conditions(), CONDITIONS, "Bệnh nền");
        List<String> pain = codes(req.painAreas(), PAIN_AREAS, "Vùng đau");
        if (req.dob() != null && (req.dob().isAfter(LocalDate.now(VIETNAM).minusYears(5)) || req.dob().isBefore(LocalDate.of(1900, 1, 1)))) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "Ngày sinh không hợp lệ");
        }
        boolean injuries = !pain.isEmpty();
        boolean heart = conditions.stream().anyMatch(HEART_OR_BP::contains);
        boolean pregnant = Boolean.TRUE.equals(req.isPregnant());
        boolean surgery = Boolean.TRUE.equals(req.recentSurgery());

        var params = new MapSqlParameterSource("me", me.getId())
                .addValue("height", req.heightCm()).addValue("weight", req.weightKg())
                .addValue("experience", blankToNull(req.experienceLevel())).addValue("intensity", blankToNull(req.preferredIntensity()))
                .addValue("goals", String.join(",", goals)).addValue("conditions", String.join(",", conditions)).addValue("pain", String.join(",", pain))
                .addValue("injuries", injuries).addValue("injuryDetails", injuries ? blankToNull(req.injuryDetails()) : null)
                .addValue("heart", heart)
                .addValue("surgery", surgery).addValue("surgeryDetails", surgery ? blankToNull(req.surgeryDetails()) : null)
                .addValue("pregnant", pregnant).addValue("week", pregnant ? req.pregnancyWeek() : null)
                .addValue("postpartum", Boolean.TRUE.equals(req.postpartum()))
                .addValue("medications", blankToNull(req.medications())).addValue("allergies", blankToNull(req.allergies()))
                .addValue("clearance", req.doctorClearance())
                .addValue("contactName", blankToNull(req.emergencyContactName()))
                .addValue("contactPhone", blankToNull(req.emergencyContactPhone()))
                .addValue("contactRelation", blankToNull(req.emergencyContactRelation()))
                .addValue("notes", blankToNull(req.medicalNotes()));
        jdbc.update("""
                INSERT INTO yoga.student_medical_profiles(student_id,height_cm,weight_kg,experience_level,preferred_intensity,goals,conditions,
                  pain_areas,has_injuries,injury_details,has_heart_or_bp,recent_surgery,surgery_details,is_pregnant,pregnancy_week,postpartum,
                  medications,allergies,doctor_clearance,emergency_contact_name,emergency_contact_phone,emergency_contact_relation,
                  medical_notes,updated_by)
                VALUES(:me,:height,:weight,:experience,:intensity,COALESCE(string_to_array(NULLIF(:goals,''),','),'{}'),
                  COALESCE(string_to_array(NULLIF(:conditions,''),','),'{}'),COALESCE(string_to_array(NULLIF(:pain,''),','),'{}'),
                  :injuries,:injuryDetails,:heart,:surgery,:surgeryDetails,:pregnant,:week,:postpartum,:medications,:allergies,:clearance,
                  :contactName,:contactPhone,:contactRelation,:notes,:me)
                ON CONFLICT (student_id) DO UPDATE SET height_cm=EXCLUDED.height_cm,weight_kg=EXCLUDED.weight_kg,
                  experience_level=EXCLUDED.experience_level,preferred_intensity=EXCLUDED.preferred_intensity,goals=EXCLUDED.goals,
                  conditions=EXCLUDED.conditions,pain_areas=EXCLUDED.pain_areas,has_injuries=EXCLUDED.has_injuries,
                  injury_details=EXCLUDED.injury_details,has_heart_or_bp=EXCLUDED.has_heart_or_bp,recent_surgery=EXCLUDED.recent_surgery,
                  surgery_details=EXCLUDED.surgery_details,is_pregnant=EXCLUDED.is_pregnant,pregnancy_week=EXCLUDED.pregnancy_week,
                  postpartum=EXCLUDED.postpartum,medications=EXCLUDED.medications,allergies=EXCLUDED.allergies,
                  doctor_clearance=EXCLUDED.doctor_clearance,emergency_contact_name=EXCLUDED.emergency_contact_name,
                  emergency_contact_phone=EXCLUDED.emergency_contact_phone,emergency_contact_relation=EXCLUDED.emergency_contact_relation,
                  medical_notes=EXCLUDED.medical_notes,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP""", params);
        // Birth date and gender live on the user (managed entity in this transaction): saved with it on commit.
        me.setDob(req.dob());
        me.setGender(blankToNull(req.gender()));
        return mine();
    }

    /** Known codes only, upper case, no duplicates; anything else is refused rather than silently dropped. */
    static List<String> codes(List<String> values, Set<String> allowed, String label) {
        if (values == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String code = v == null ? "" : v.trim().toUpperCase();
            if (!allowed.contains(code)) throw new BusinessException(CommonErrorCode.BAD_REQUEST, label + " không hợp lệ: " + v);
            out.add(code);
        }
        return List.copyOf(out);
    }

    private static List<String> list(Array array) throws SQLException {
        if (array == null) return List.of();
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
