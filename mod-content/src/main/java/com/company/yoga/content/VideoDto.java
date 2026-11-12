package com.company.yoga.content;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class VideoDto {

    private VideoDto() {}

    static final String LEVELS = "BEGINNER|INTERMEDIATE|ADVANCED|ALL_LEVELS";

    // ---- Content management ----

    public record SaveVideoReq(
            @NotBlank(message = "Tiêu đề video không được để trống") @Size(max = 200) String title,
            String description,
            @Pattern(regexp = LEVELS, message = "Cấp độ không hợp lệ") String level,
            UUID instructorId,
            UUID classTypeId,
            Boolean isFreePreview,
            Boolean notForPregnancy,
            Boolean notForHeartBp,
            Boolean notForInjuries,
            String contraindications,
            List<UUID> tagIds
    ) {}

    public record AdminVideo(
            UUID id, String title, String description, String level, Integer durationSeconds,
            UUID instructorId, String instructorName, UUID classTypeId, String status, String processingError,
            boolean isFreePreview, boolean notForPregnancy, boolean notForHeartBp, boolean notForInjuries, String contraindications,
            String thumbnailUrl, List<UUID> tagIds, UUID createdBy, Instant publishedAt, Instant createdAt, Instant updatedAt,
            long viewers, boolean canEdit,
            /** Live recordings (V20): source session, course it belongs to, Zoom→Bunny import state. */
            UUID sourceScheduleId, UUID courseClassId, String courseName, String recordingStatus, String recordingError
    ) {}

    public record UploadSession(UUID videoId, String endpoint, String libraryId, String providerVideoId, String signature,
                                long expiresEpochSeconds) {}

    public record Preview(String embedUrl, Instant expiresAt) {}

    public record Tag(UUID id, String code, String name, long videoCount) {}

    public record SaveTagReq(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,50}", message = "Mã chủ đề chỉ gồm chữ không dấu, số và dấu gạch dưới") String code,
            @NotBlank @Size(max = 100) String name) {}

    public record SaveCollectionReq(
            @NotBlank(message = "Tên lộ trình không được để trống") @Size(max = 200) String title,
            String description,
            @Pattern(regexp = LEVELS, message = "Cấp độ không hợp lệ") String level,
            Boolean isPublished,
            List<UUID> videoIds) {}

    public record AdminCollection(UUID id, String title, String description, String level, boolean isPublished,
                                  List<CollectionItem> items, Instant updatedAt) {}

    public record CollectionItem(UUID videoId, String title, int position, Integer durationSeconds, String status) {}

    // ---- Student library ----

    public record Access(boolean hasPass, LocalDate passEndDate, boolean waiverAccepted, String waiverVersion, int maxDevices) {}

    public record LibraryVideo(
            UUID id, String title, String description, String level, Integer durationSeconds, String instructorName,
            String thumbnailUrl, List<String> tags, boolean isFreePreview, boolean locked,
            int positionSeconds, int watchedSeconds, boolean completed, Instant publishedAt) {}

    public record LibraryPage(List<LibraryVideo> items, int page, int size, long total) {}

    public record VideoDetail(LibraryVideo video, String contraindications, List<String> warnings, Access access) {}

    public record PlaybackReq(@NotBlank @Size(max = 100) String deviceKey) {}

    public record Playback(UUID sessionId, String embedUrl, Instant expiresAt, int heartbeatSeconds, int startAtSeconds,
                           String watermark, List<String> warnings) {}

    public record ProgressReq(
            @NotNull UUID sessionId,
            @NotNull @Min(0) Integer positionSeconds,
            @NotNull @Min(0) Integer watchedSeconds) {}

    public record Progress(int positionSeconds, int watchedSeconds, boolean completed, Instant sessionExpiresAt) {}

    public record StudentCollection(UUID id, String title, String description, String level, int total, int completed,
                                    List<LibraryVideo> items) {}
}
