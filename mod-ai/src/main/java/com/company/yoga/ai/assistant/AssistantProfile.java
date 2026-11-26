package com.company.yoga.ai.assistant;

import java.util.List;
import java.util.UUID;

/**
 * One kind of assistant: who may use it, which feature configuration (model, price, limits), prompt and tools it
 * has. {@link AssistantService} picks the profile from the caller's role, so each role only ever sees its own tools
 * and its own conversations.
 */
public interface AssistantProfile {

    /** Signed-in user as the assistant sees them. */
    record Caller(UUID userId, String role, UUID homeBranchId) {
    }

    /** Stored in {@code ai_conversations.kind}. */
    String kind();

    /** Key under {@code yoga.ai.features}. */
    String featureKey();

    /** Prompt file name under {@code ai/prompts}. */
    String prompt();

    boolean accepts(String role);

    /** Staff quota or student quota. */
    boolean staff();

    /** First line of each question: today's date and who is asking. Not in the system prompt, to keep it cacheable. */
    String context(Caller caller);

    /** Tools for this caller (staff tools depend on the role). */
    List<AiTool> tools(Caller caller);
}
