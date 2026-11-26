package com.company.yoga.ai.assistant;

import com.company.yoga.ai.AiProvider;
import java.util.Map;

/**
 * A function the assistant may call. It runs as the signed-in user, so the called service applies the usual
 * permission and branch checks; a refusal is returned to the model as {@code {"error": "..."}}.
 */
public interface AiTool {

    AiProvider.ToolSpec spec();

    /** Returns report aggregates only: never customer names, phones or other personal details. */
    Map<String, Object> run(Map<String, Object> args);
}
