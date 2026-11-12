package com.company.yoga.schedule.course;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CourseDto {
    private CourseDto() {}
    public record ProgramSession(@NotBlank @Size(max=200) String title,
        @NotBlank @Size(max=5000) String content, @Min(15) @Max(240) int durationMinutes) {}
    public record CreateCourse(@NotBlank @Size(max=50) String code, @NotBlank @Size(max=150) String name,
        @Size(max=5000) String description, @NotNull UUID classTypeId,
        @Min(1) @Max(120) int totalSessions, @Min(15) @Max(240) int defaultDurationMinutes,
        @NotNull @DecimalMin("0") BigDecimal defaultFee,
        @NotEmpty @Size(max=120) List<@Valid ProgramSession> sessions) {}
    public record Session(@NotNull UUID roomId, @NotNull UUID instructorId,
        @NotNull Instant startTime, @NotNull Instant endTime, @Size(max=200) String title) {}
    public record CreateClass(@NotNull UUID courseId, @NotNull UUID branchId,
        @NotBlank @Size(max=50) String code, @NotBlank @Size(max=150) String name,
        @Min(1) @Max(200) int maxCapacity, @NotNull @DecimalMin("0") BigDecimal tuitionFee,
        @NotEmpty @Size(max=120) List<@Valid Session> sessions,
        /** IN_PERSON (default) or ONLINE: every session of an online course is in an online room (V16). */
        @Pattern(regexp="IN_PERSON|ONLINE", message="Hình thức học phải là IN_PERSON hoặc ONLINE") String deliveryMode,
        /** Online courses: record every session; recordings are for the students of this course only (V20). */
        Boolean recordSessions) {
        public CreateClass(UUID courseId, UUID branchId, String code, String name, int maxCapacity, BigDecimal tuitionFee, List<Session> sessions) {
            this(courseId, branchId, code, name, maxCapacity, tuitionFee, sessions, null, null);
        }
        public CreateClass(UUID courseId, UUID branchId, String code, String name, int maxCapacity, BigDecimal tuitionFee, List<Session> sessions, String deliveryMode) {
            this(courseId, branchId, code, name, maxCapacity, tuitionFee, sessions, deliveryMode, null);
        }
        public String mode() { return "ONLINE".equals(deliveryMode) ? "ONLINE" : "IN_PERSON"; }
    }
    public record Enroll(@NotNull UUID studentId) {}
    public record ChangeSession(@NotNull UUID roomId, @NotNull UUID instructorId,
        @NotNull Instant startTime, @NotNull Instant endTime, @Size(max=200) String title) {}
    public record Conflict(int sessionNumber, String message) {}
}
