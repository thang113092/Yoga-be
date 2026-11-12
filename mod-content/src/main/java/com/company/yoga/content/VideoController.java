package com.company.yoga.content;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Thư viện video", description = "Video theo yêu cầu: thư viện học viên, quản lý nội dung, webhook Bunny")
@RestController
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoLibraryService library;
    private final VideoAdminService admin;
    private final BunnyWebhookService webhooks;

    // ---- Student ----

    @Operation(summary = "Quyền xem thư viện của học viên (gói online/kết hợp, cam kết an toàn)")
    @GetMapping("/api/v1/me/video-access")
    public ApiResponse<VideoDto.Access> access() {
        return ApiResponse.success(library.access());
    }

    @Operation(summary = "Xác nhận cam kết an toàn trước khi xem video lần đầu")
    @PostMapping("/api/v1/me/video-waiver")
    public ApiResponse<VideoDto.Access> acceptWaiver() {
        return ApiResponse.success(library.acceptWaiver());
    }

    @Operation(summary = "Thư viện video đã xuất bản, kèm tiến độ; video khóa khi chưa có gói online")
    @GetMapping("/api/v1/me/videos")
    public ApiResponse<VideoDto.LibraryPage> list(@RequestParam(required = false) String level, @RequestParam(required = false) String tag,
                                                  @RequestParam(required = false) Integer maxMinutes, @RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "false") boolean inProgress,
                                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size) {
        return ApiResponse.success(library.list(level, tag, maxMinutes, q, inProgress, page, size));
    }

    @Operation(summary = "Chi tiết video, cảnh báo theo hồ sơ sức khỏe")
    @GetMapping("/api/v1/me/videos/{id}")
    public ApiResponse<VideoDto.VideoDetail> detail(@PathVariable UUID id) {
        return ApiResponse.success(library.detail(id));
    }

    @Operation(summary = "Mở phiên xem: kiểm quyền, giới hạn thiết bị, trả URL trình phát có chữ ký")
    @PostMapping("/api/v1/me/videos/{id}/playback")
    public ApiResponse<VideoDto.Playback> playback(@PathVariable UUID id, @Valid @RequestBody VideoDto.PlaybackReq req) {
        return ApiResponse.success(library.startPlayback(id, req.deviceKey()));
    }

    @Operation(summary = "Gửi tiến độ xem (nhịp tim của trình phát)")
    @PutMapping("/api/v1/me/videos/{id}/progress")
    public ApiResponse<VideoDto.Progress> progress(@PathVariable UUID id, @Valid @RequestBody VideoDto.ProgressReq req) {
        return ApiResponse.success(library.reportProgress(id, req));
    }

    @Operation(summary = "Đóng phiên xem, giải phóng thiết bị")
    @PostMapping("/api/v1/me/playback-sessions/{sessionId}/end")
    public ApiResponse<Void> endSession(@PathVariable UUID sessionId) {
        library.endSession(sessionId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Các lộ trình đã xuất bản, kèm tiến độ")
    @GetMapping("/api/v1/me/video-collections")
    public ApiResponse<List<VideoDto.StudentCollection>> myCollections() {
        return ApiResponse.success(library.collections());
    }

    @GetMapping("/api/v1/me/video-collections/{id}")
    public ApiResponse<VideoDto.StudentCollection> myCollection(@PathVariable UUID id) {
        return ApiResponse.success(library.collection(id));
    }

    // ---- Content management ----

    @Operation(summary = "Danh sách video (quản lý: tất cả; HLV: video của mình)")
    @GetMapping("/api/v1/videos")
    public ApiResponse<List<VideoDto.AdminVideo>> adminList(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return ApiResponse.success(admin.list(status, q));
    }

    @GetMapping("/api/v1/videos/{id}")
    public ApiResponse<VideoDto.AdminVideo> adminGet(@PathVariable UUID id) {
        return ApiResponse.success(admin.get(id));
    }

    @PostMapping("/api/v1/videos")
    public ApiResponse<VideoDto.AdminVideo> create(@Valid @RequestBody VideoDto.SaveVideoReq req) {
        return ApiResponse.success(admin.create(req));
    }

    @PutMapping("/api/v1/videos/{id}")
    public ApiResponse<VideoDto.AdminVideo> update(@PathVariable UUID id, @Valid @RequestBody VideoDto.SaveVideoReq req) {
        return ApiResponse.success(admin.update(id, req));
    }

    @DeleteMapping("/api/v1/videos/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        admin.delete(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Lấy thông tin tải file lên Bunny (TUS) cho video")
    @PostMapping("/api/v1/videos/{id}/upload-session")
    public ApiResponse<VideoDto.UploadSession> uploadSession(@PathVariable UUID id) {
        return ApiResponse.success(admin.uploadSession(id));
    }

    @Operation(summary = "Hỏi Bunny trạng thái chuyển mã ngay")
    @PostMapping("/api/v1/videos/{id}/refresh")
    public ApiResponse<VideoDto.AdminVideo> refresh(@PathVariable UUID id) {
        return ApiResponse.success(admin.refresh(id));
    }

    @PostMapping("/api/v1/videos/{id}/publish")
    public ApiResponse<VideoDto.AdminVideo> publish(@PathVariable UUID id) {
        return ApiResponse.success(admin.publish(id));
    }

    @PostMapping("/api/v1/videos/{id}/unpublish")
    public ApiResponse<VideoDto.AdminVideo> unpublish(@PathVariable UUID id) {
        return ApiResponse.success(admin.unpublish(id));
    }

    @PostMapping("/api/v1/videos/{id}/archive")
    public ApiResponse<VideoDto.AdminVideo> archive(@PathVariable UUID id) {
        return ApiResponse.success(admin.archive(id));
    }

    @PostMapping("/api/v1/videos/{id}/restore")
    public ApiResponse<VideoDto.AdminVideo> restore(@PathVariable UUID id) {
        return ApiResponse.success(admin.restore(id));
    }

    @Operation(summary = "Xem thử video (nhân viên), không tính tiến độ")
    @PostMapping("/api/v1/videos/{id}/preview")
    public ApiResponse<VideoDto.Preview> preview(@PathVariable UUID id) {
        return ApiResponse.success(admin.preview(id));
    }

    @GetMapping("/api/v1/video-tags")
    public ApiResponse<List<VideoDto.Tag>> tags() {
        return ApiResponse.success(admin.tags());
    }

    @PostMapping("/api/v1/video-tags")
    public ApiResponse<VideoDto.Tag> createTag(@Valid @RequestBody VideoDto.SaveTagReq req) {
        return ApiResponse.success(admin.createTag(req));
    }

    @DeleteMapping("/api/v1/video-tags/{id}")
    public ApiResponse<Void> deleteTag(@PathVariable UUID id) {
        admin.deleteTag(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/api/v1/video-collections")
    public ApiResponse<List<VideoDto.AdminCollection>> collections() {
        return ApiResponse.success(admin.collections());
    }

    @PostMapping("/api/v1/video-collections")
    public ApiResponse<VideoDto.AdminCollection> createCollection(@Valid @RequestBody VideoDto.SaveCollectionReq req) {
        return ApiResponse.success(admin.createCollection(req));
    }

    @PutMapping("/api/v1/video-collections/{id}")
    public ApiResponse<VideoDto.AdminCollection> updateCollection(@PathVariable UUID id, @Valid @RequestBody VideoDto.SaveCollectionReq req) {
        return ApiResponse.success(admin.updateCollection(id, req));
    }

    @DeleteMapping("/api/v1/video-collections/{id}")
    public ApiResponse<Void> deleteCollection(@PathVariable UUID id) {
        admin.deleteCollection(id);
        return ApiResponse.success(null);
    }

    /** Public endpoint: trust comes from the secret in the URL, and the state is read back from Bunny. */
    @Operation(summary = "Webhook Bunny Stream (bí mật trong tham số token)")
    @PostMapping("/api/v1/integration-webhooks/bunny")
    public ResponseEntity<Map<String, Object>> bunny(@RequestParam(value = "token", required = false) String token, @RequestBody String body) {
        if (!webhooks.handle(token, body)) {
            log.warn("Rejected Bunny webhook with a wrong secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
