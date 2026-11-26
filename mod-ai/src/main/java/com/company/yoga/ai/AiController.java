package com.company.yoga.ai;

import com.company.yoga.ai.assistant.AssistantService;
import com.company.yoga.ai.assistant.HealthConsentService;
import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "AI", description = "Tính năng AI (Gemini): gợi ý chủ đề video, trợ lý cho quản lý, học viên, lễ tân và HLV")
@RestController
@Slf4j
public class AiController {

    private final VideoTaggingService tagging;
    private final AssistantService assistant;
    private final HealthConsentService consents;
    private final TaskExecutor streamExecutor;

    public AiController(VideoTaggingService tagging, AssistantService assistant, HealthConsentService consents,
                        @Qualifier("aiStreamExecutor") TaskExecutor streamExecutor) {
        this.tagging = tagging;
        this.assistant = assistant;
        this.consents = consents;
        this.streamExecutor = streamExecutor;
    }

    public record Status(boolean videoTagging, boolean managerChat, boolean studentChat, boolean staffChat) {
    }

    public record ReviewReq(
            @NotBlank @Pattern(regexp = "APPLIED|REJECTED", message = "Trạng thái phải là APPLIED hoặc REJECTED") String status) {
    }

    /** Unsaved title/description from the open form; when absent the saved values are used. */
    public record SuggestReq(@Size(max = 200) String title, @Size(max = 5000) String description) {
    }

    public record AskReq(@NotBlank(message = "Vui lòng nhập câu hỏi")
                         @Size(max = AssistantService.MAX_QUESTION, message = "Câu hỏi tối đa 2000 ký tự") String text) {
    }

    public record ConsentReq(@NotNull Boolean consented) {
    }

    @Operation(summary = "Tính năng AI nào đang bật (để giao diện ẩn/hiện nút)")
    @GetMapping("/api/v1/ai/status")
    public ApiResponse<Status> status() {
        return ApiResponse.success(new Status(tagging.available(), assistant.available(AiProperties.MANAGER_CHAT),
                assistant.available(AiProperties.STUDENT_CHAT), assistant.available(AiProperties.STAFF_CHAT)));
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

    // ---- assistants (the caller's role picks the assistant) ----

    @Operation(summary = "Hội thoại với trợ lý của tôi, mới nhất trước")
    @GetMapping("/api/v1/ai/conversations")
    public ApiResponse<List<AssistantService.Conversation>> conversations() {
        return ApiResponse.success(assistant.conversations());
    }

    @Operation(summary = "Bắt đầu hội thoại mới với trợ lý")
    @PostMapping("/api/v1/ai/conversations")
    public ApiResponse<AssistantService.Conversation> create() {
        return ApiResponse.success(assistant.create());
    }

    @Operation(summary = "Câu hỏi và câu trả lời của một hội thoại")
    @GetMapping("/api/v1/ai/conversations/{id}/messages")
    public ApiResponse<List<AssistantService.Message>> messages(@PathVariable UUID id) {
        return ApiResponse.success(assistant.messages(id));
    }

    @Operation(summary = "Hỏi trợ lý; trả về câu trả lời khi trợ lý đã tra xong (kèm nút đề xuất, ví dụ đặt chỗ)")
    @PostMapping("/api/v1/ai/conversations/{id}/messages")
    public ApiResponse<AssistantService.Answer> ask(@PathVariable UUID id, @Valid @RequestBody AskReq req) {
        return ApiResponse.success(assistant.ask(id, req.text()));
    }

    /**
     * Same as {@link #ask} but streams Server-Sent Events: {@code delta} {text}, {@code reset} (drop the preamble shown
     * so far), {@code tool} {name}, then {@code done} with the stored answer, or {@code error} {code, message}.
     */
    @Operation(summary = "Hỏi trợ lý, câu trả lời hiện dần (text/event-stream)")
    @PostMapping(value = "/api/v1/ai/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@PathVariable UUID id, @Valid @RequestBody AskReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(180_000L);
        SecurityContext context = SecurityContextHolder.getContext();
        Sender send = new Sender(emitter);
        try {
            streamExecutor.execute(() -> {
                SecurityContextHolder.setContext(context);
                try {
                    AssistantService.Answer answer = assistant.ask(id, req.text(), new AssistantService.Listener() {
                        @Override
                        public void onText(String delta) {
                            send.event("delta", Map.of("text", delta));
                        }

                        @Override
                        public void onReset() {
                            send.event("reset", Map.of());
                        }

                        @Override
                        public void onTool(String name) {
                            send.event("tool", Map.of("name", name));
                        }
                    });
                    send.event("done", answer);
                } catch (BusinessException e) {
                    send.event("error", Map.of("code", e.getErrorCode().getCode(), "message", e.getMessage()));
                } catch (RuntimeException e) {
                    log.error("Streamed assistant answer failed", e);
                    send.event("error", Map.of("code", "AI_500", "message", "Trợ lý gặp lỗi. Vui lòng thử lại."));
                } finally {
                    SecurityContextHolder.clearContext();
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            send.event("error", Map.of("code", AiResultCodes.BUSY.getCode(), "message", AiResultCodes.BUSY.getMessage()));
            emitter.complete();
        }
        return emitter;
    }

    /** Sends events until the client goes away; after that the answer still completes and is stored. */
    private static final class Sender {
        private final SseEmitter emitter;
        private volatile boolean gone;

        Sender(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onError(e -> gone = true);
            emitter.onTimeout(() -> gone = true);
        }

        void event(String name, Object data) {
            if (gone) return;
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                gone = true;
            }
        }
    }

    @Operation(summary = "Xóa hội thoại của tôi")
    @DeleteMapping("/api/v1/ai/conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        assistant.delete(id);
        return ApiResponse.success(null);
    }

    // ---- health-aware suggestions (students) ----

    @Operation(summary = "Học viên đã cho trợ lý dùng 3 mục có/không của hồ sơ sức khỏe để gợi ý video hay chưa")
    @GetMapping("/api/v1/ai/health-consent")
    public ApiResponse<HealthConsentService.Consent> healthConsent() {
        return ApiResponse.success(consents.mine());
    }

    @Operation(summary = "Bật/tắt cho trợ lý dùng hồ sơ sức khỏe (chấn thương, tim/huyết áp, mang thai) để gợi ý video")
    @PutMapping("/api/v1/ai/health-consent")
    public ApiResponse<HealthConsentService.Consent> setHealthConsent(@Valid @RequestBody ConsentReq req) {
        return ApiResponse.success(consents.set(req.consented()));
    }
}
