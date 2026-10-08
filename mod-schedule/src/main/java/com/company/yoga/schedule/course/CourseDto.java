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
        @NotEmpty @Size(max=120) List<@Valid Session> sessions) {}
    public record Enroll(@NotNull UUID studentId) {}
    public record ChangeSession(@NotNull UUID roomId, @NotNull UUID instructorId,
        @NotNull Instant startTime, @NotNull Instant endTime, @Size(max=200) String title) {}
    public record Conflict(int sessionNumber, String message) {}
}
