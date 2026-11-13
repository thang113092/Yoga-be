package com.company.yoga.ai;

import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI", description = "Tính năng AI (Gemini): gợi ý chủ đề và mô tả video")
@RestController
@RequiredArgsConstructor
public class AiController {

    private final VideoTaggingService tagging;

    public record Status(boolean videoTagging) {
    }

    public record ReviewReq(
            @NotBlank @Pattern(regexp = "APPLIED|REJECTED", message = "Trạng thái phải là APPLIED hoặc REJECTED") String status) {
    }

    @Operation(summary = "Tính năng AI nào đang bật (để giao diện ẩn/hiện nút)")
    @GetMapping("/api/v1/ai/status")
    public ApiResponse<Status> status() {
        return ApiResponse.success(new Status(tagging.available()));
    }

    /** Unsaved title/description from the open form; when absent the saved values are used. */
    public record SuggestReq(@Size(max = 200) String title, @Size(max = 5000) String description) {
    }

    @Operation(summary = "AI gợi ý chủ đề, cấp độ, mô tả và lưu ý an toàn cho video (nhân viên xem lại trước khi lưu)")
    @PostMapping("/api/v1/ai/videos/{videoId}/tag-suggestions")
    public ApiResponse<VideoTaggingService.TagSuggestion> suggest(@PathVariable UUID videoId,
                                                                  @Valid @RequestBody(required = false) SuggestReq req) {
        return ApiResponse.success(tagging.suggest(videoId, req == null ? null : req.title(), req == null ? null : req.description()));
    }

    @Operation(summary = "Ghi nhận nhân viên đã áp dụng hay bỏ qua gợi ý")
    @PostMapping("/api/v1/ai/tag-suggestions/{id}/review")
    public ApiResponse<Void> review(@PathVariable UUID id, @Valid @RequestBody ReviewReq req) {
        tagging.review(id, req.status());
        return ApiResponse.success(null);
    }
}
