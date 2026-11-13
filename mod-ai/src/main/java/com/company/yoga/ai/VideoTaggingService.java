package com.company.yoga.ai;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.content.VideoAdminService;
import com.company.yoga.content.VideoDto;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Suggests tags, level, description and safety flags for a video from its title and description. Access follows
 * {@link VideoAdminService#get}: whoever may open the video in content management may ask. The suggestion is stored
 * and only returned; staff copy it into the form and save through the normal video API.
 */
@Service
@Slf4j
public class VideoTaggingService {

    static final String PROMPT = "video-tagging";
    static final List<String> LEVELS = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED", "ALL_LEVELS");
    static final int MAX_TAGS = 4;
    static final int MAX_DESCRIPTION = 1500;
    static final int MAX_CONTRAINDICATIONS = 500;

    public record TagSuggestion(UUID id, UUID videoId, List<UUID> tagIds, List<String> tagCodes, String level,
                                String description, String contraindications, Boolean notForPregnancy,
                                Boolean notForHeartBp, Boolean notForInjuries, String model) {
    }

    private final AiProperties properties;
    private final AiProvider provider;
    private final AiUsageService usage;
    private final Prompts prompts;
    private final VideoAdminService videos;
    private final AccessPolicy accessPolicy;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public VideoTaggingService(AiProperties properties, AiProvider provider, AiUsageService usage, Prompts prompts,
                               VideoAdminService videos, AccessPolicy accessPolicy, NamedParameterJdbcTemplate jdbc,
                               ObjectMapper json) {
        this.properties = properties;
        this.provider = provider;
        this.usage = usage;
        this.prompts = prompts;
        this.videos = videos;
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean available() {
        return properties.isOn(AiProperties.VIDEO_TAGGING) && provider.isConfigured();
    }

    public TagSuggestion suggest(UUID videoId) {
        return suggest(videoId, null, null);
    }

    /**
     * @param title       unsaved title from the form, or null for the saved one
     * @param description unsaved description from the form, or null for the saved one
     */
    public TagSuggestion suggest(UUID videoId, String title, String description) {
        if (!available()) throw new BusinessException(AiResultCodes.DISABLED);
        VideoDto.AdminVideo saved = videos.get(videoId);
        VideoDto.AdminVideo video = withText(saved, title, description);
        UUID actor = accessPolicy.actor().getId();
        usage.checkQuota(actor, true);

        List<VideoDto.Tag> catalog = videos.tags();
        AiProperties.Feature feature = properties.feature(AiProperties.VIDEO_TAGGING);
        Prompts.Prompt prompt = prompts.get(PROMPT);
        var request = new AiProvider.JsonRequest(feature.model(), prompt.text(), userText(video, catalog), schema(catalog),
                feature.maxOutputTokens(), feature.thinkingLevel(), feature.thinkingBudget());

        AiProvider.Result result;
        try {
            result = provider.generateJson(request);
        } catch (AiProvider.ProviderException e) {
            if (e.usage().inputTokens() > 0 || e.usage().outputTokens() > 0) usage.record(actor, feature, e.usage());
            throw new BusinessException(switch (e.failure()) {
                case BLOCKED -> AiResultCodes.BLOCKED;
                case RATE_LIMITED, UNAVAILABLE -> AiResultCodes.BUSY;
                default -> AiResultCodes.PROVIDER_ERROR;
            });
        }
        long cost = usage.record(actor, feature, result.usage());

        Map<String, Object> payload = sanitize(result.text(), catalog, video);
        UUID id = jdbc.queryForObject("""
                INSERT INTO yoga.video_ai_suggestions(video_id,requested_by,provider,model,prompt_version,payload,
                  input_tokens,output_tokens,cost_micro_usd)
                VALUES(:video,:me,:provider,:model,:prompt,CAST(:payload AS JSONB),:input,:output,:cost) RETURNING id""",
                new MapSqlParameterSource("video", videoId).addValue("me", actor).addValue("provider", provider.name())
                        .addValue("model", feature.model()).addValue("prompt", prompt.version()).addValue("payload", write(payload))
                        .addValue("input", (int) Math.min(Integer.MAX_VALUE, result.usage().inputTokens()))
                        .addValue("output", (int) Math.min(Integer.MAX_VALUE, result.usage().outputTokens()))
                        .addValue("cost", cost), UUID.class);
        return toSuggestion(id, videoId, payload, catalog, feature.model());
    }

    /** Records whether staff used the suggestion (APPLIED) or not (REJECTED). */
    public void review(UUID suggestionId, String status) {
        if (!"APPLIED".equals(status) && !"REJECTED".equals(status)) throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        UUID videoId = jdbc.query("SELECT video_id FROM yoga.video_ai_suggestions WHERE id=:id",
                new MapSqlParameterSource("id", suggestionId), rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (videoId == null) throw new BusinessException(AiResultCodes.SUGGESTION_NOT_FOUND);
        videos.get(videoId);
        UUID actor = accessPolicy.actor().getId();
        int updated = jdbc.update("""
                UPDATE yoga.video_ai_suggestions SET status=:status,reviewed_by=:me,reviewed_at=CURRENT_TIMESTAMP
                WHERE id=:id AND status='PENDING'""",
                new MapSqlParameterSource("id", suggestionId).addValue("status", status).addValue("me", actor));
        if (updated == 0) throw new BusinessException(AiResultCodes.SUGGESTION_REVIEWED);
    }

    // ---------------------------------------------------------------- prompt

    private static VideoDto.AdminVideo withText(VideoDto.AdminVideo v, String title, String description) {
        String t = title == null || title.isBlank() ? v.title() : title.trim();
        String d = description == null ? v.description() : description.trim();
        if (t.equals(v.title()) && java.util.Objects.equals(d, v.description())) return v;
        return new VideoDto.AdminVideo(v.id(), t, d, v.level(), v.durationSeconds(), v.instructorId(), v.instructorName(),
                v.classTypeId(), v.status(), v.processingError(), v.isFreePreview(), v.notForPregnancy(), v.notForHeartBp(),
                v.notForInjuries(), v.contraindications(), v.thumbnailUrl(), v.tagIds(), v.createdBy(), v.publishedAt(),
                v.createdAt(), v.updatedAt(), v.viewers(), v.canEdit(), v.sourceScheduleId(), v.courseClassId(), v.courseName(),
                v.recordingStatus(), v.recordingError());
    }

    /** Only what is needed: no instructor name, no student data. JSON keeps quotes safe; '<' is escaped. */
    String userText(VideoDto.AdminVideo video, List<VideoDto.Tag> catalog) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", video.title());
        data.put("description", video.description());
        data.put("currentLevel", video.level());
        data.put("durationMinutes", video.durationSeconds() == null ? null : Math.round(video.durationSeconds() / 60.0));
        data.put("notForPregnancy", video.notForPregnancy());
        data.put("notForHeartBp", video.notForHeartBp());
        data.put("notForInjuries", video.notForInjuries());
        data.put("contraindications", video.contraindications());
        StringBuilder tags = new StringBuilder();
        for (VideoDto.Tag tag : catalog) tags.append("- ").append(tag.code()).append(": ").append(tag.name()).append('\n');
        return "<chu_de>\n" + escape(tags.toString()) + "</chu_de>\n\n<video>\n" + escape(write(data)) + "\n</video>";
    }

    private static String escape(String s) {
        return s.replace("<", "\\u003c").replace(">", "\\u003e");
    }

    static Map<String, Object> schema(List<VideoDto.Tag> catalog) {
        Map<String, Object> tagItems = new LinkedHashMap<>();
        tagItems.put("type", "string");
        if (!catalog.isEmpty()) tagItems.put("enum", catalog.stream().map(VideoDto.Tag::code).toList());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tagCodes", Map.of("type", "array", "items", tagItems, "description", "1-4 mã chủ đề trong danh sách"));
        properties.put("level", Map.of("type", "string", "enum", LEVELS));
        properties.put("description", Map.of("type", "string", "description", "2-4 câu tiếng Việt"));
        properties.put("contraindications", Map.of("type", "string", "nullable", true));
        properties.put("notForPregnancy", Map.of("type", "boolean"));
        properties.put("notForHeartBp", Map.of("type", "boolean"));
        properties.put("notForInjuries", Map.of("type", "boolean"));
        return Map.of("type", "object", "properties", properties, "required",
                List.of("tagCodes", "level", "description", "notForPregnancy", "notForHeartBp", "notForInjuries"));
    }

    // ---------------------------------------------------------------- answer

    /**
     * Never trusts the model: unknown tags are dropped, the level must be one we know, text is trimmed and capped.
     * Safety flags can only be added, never cleared, compared with the video as saved.
     */
    Map<String, Object> sanitize(String text, List<VideoDto.Tag> catalog, VideoDto.AdminVideo video) {
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (JsonProcessingException e) {
            log.warn("Video tagging answer is not JSON ({} chars)", text.length());
            throw new BusinessException(AiResultCodes.PROVIDER_ERROR);
        }
        if (node == null || !node.isObject()) throw new BusinessException(AiResultCodes.PROVIDER_ERROR);

        Set<String> known = new LinkedHashSet<>(catalog.stream().map(VideoDto.Tag::code).toList());
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode code : node.path("tagCodes")) {
            String c = code.asText("").trim().toUpperCase();
            if (known.contains(c) && codes.size() < MAX_TAGS) codes.add(c);
        }
        String level = node.path("level").asText("");
        if (!LEVELS.contains(level)) level = video.level();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tagCodes", new ArrayList<>(codes));
        out.put("level", level);
        out.put("description", cap(node.path("description").asText(null), MAX_DESCRIPTION));
        out.put("contraindications", cap(node.path("contraindications").isNull() ? null : node.path("contraindications").asText(null), MAX_CONTRAINDICATIONS));
        out.put("notForPregnancy", video.notForPregnancy() || node.path("notForPregnancy").asBoolean(false));
        out.put("notForHeartBp", video.notForHeartBp() || node.path("notForHeartBp").asBoolean(false));
        out.put("notForInjuries", video.notForInjuries() || node.path("notForInjuries").asBoolean(false));
        return out;
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max).trim() : trimmed;
    }

    @SuppressWarnings("unchecked")
    private static TagSuggestion toSuggestion(UUID id, UUID videoId, Map<String, Object> p, List<VideoDto.Tag> catalog, String model) {
        List<String> codes = (List<String>) p.get("tagCodes");
        List<UUID> ids = catalog.stream().filter(t -> codes.contains(t.code())).map(VideoDto.Tag::id).toList();
        return new TagSuggestion(id, videoId, ids, codes, (String) p.get("level"), (String) p.get("description"),
                (String) p.get("contraindications"), (Boolean) p.get("notForPregnancy"), (Boolean) p.get("notForHeartBp"),
                (Boolean) p.get("notForInjuries"), model);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
