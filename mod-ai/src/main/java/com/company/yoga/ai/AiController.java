package com.company.yoga.ai;

import com.company.yoga.ai.assistant.ManagerAssistantService;
import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI", description = "Tính năng AI (Gemini): gợi ý chủ đề video, trợ lý số liệu cho quản lý")
@RestController
@RequiredArgsConstructor
public class AiController {

    private final VideoTaggingService tagging;
    private final ManagerAssistantService assistant;

    public record Status(boolean videoTagging, boolean managerChat) {
    }

    public record ReviewReq(
            @NotBlank @Pattern(regexp = "APPLIED|REJECTED", message = "Trạng thái phải là APPLIED hoặc REJECTED") String status) {
    }

    /** Unsaved title/description from the open form; when absent the saved values are used. */
    public record SuggestReq(@Size(max = 200) String title, @Size(max = 5000) String description) {
    }

    public record AskReq(@NotBlank(message = "Vui lòng nhập câu hỏi")
                         @Size(max = ManagerAssistantService.MAX_QUESTION, message = "Câu hỏi tối đa 2000 ký tự") String text) {
    }

    @Operation(summary = "Tính năng AI nào đang bật (để giao diện ẩn/hiện nút)")
    @GetMapping("/api/v1/ai/status")
    public ApiResponse<Status> status() {
        return ApiResponse.success(new Status(tagging.available(), assistant.available()));
    }

    // ---- video tag suggestions ----

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

    // ---- manager assistant ----

    @Operation(summary = "Hội thoại trợ lý số liệu của tôi (mới nhất trước)")
    @GetMapping("/api/v1/ai/conversations")
    public ApiResponse<List<ManagerAssistantService.Conversation>> conversations() {
        return ApiResponse.success(assistant.conversations());
    }

    @Operation(summary = "Bắt đầu hội thoại mới với trợ lý số liệu")
    @PostMapping("/api/v1/ai/conversations")
    public ApiResponse<ManagerAssistantService.Conversation> create() {
        return ApiResponse.success(assistant.create());
    }

    @Operation(summary = "Câu hỏi và câu trả lời của một hội thoại")
    @GetMapping("/api/v1/ai/conversations/{id}/messages")
    public ApiResponse<List<ManagerAssistantService.Message>> messages(@PathVariable UUID id) {
        return ApiResponse.success(assistant.messages(id));
    }

    @Operation(summary = "Hỏi trợ lý; trả về câu trả lời khi trợ lý đã tra xong số liệu")
    @PostMapping("/api/v1/ai/conversations/{id}/messages")
    public ApiResponse<ManagerAssistantService.Answer> ask(@PathVariable UUID id, @Valid @RequestBody AskReq req) {
        return ApiResponse.success(assistant.ask(id, req.text()));
    }

    @Operation(summary = "Xóa hội thoại của tôi")
    @DeleteMapping("/api/v1/ai/conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        assistant.delete(id);
        return ApiResponse.success(null);
    }
}
