package com.openmason.main.systems.assistant.llm;

import java.util.Locale;
import java.util.Map;

/**
 * Known local-model limits. The live model id is DISCOVERED via
 * {@code GET /v1/models} (the local vLLM servers swap models on one port —
 * never hardcode which is up); this table only supplies context budgets and
 * thinking-mode knobs for ids we recognize.
 */
public record ModelInfo(String id, long contextTokens, long maxOutputTokens, boolean thinking) {

    private static final Map<String, ModelInfo> KNOWN = Map.of(
            "deepseek-v4-flash", new ModelInfo("deepseek-v4-flash", 1_048_576, 65_536, true),
            "qwen3.8-27b", new ModelInfo("qwen3.8-27b", 262_144, 32_768, true),
            "laguna", new ModelInfo("laguna", 262_144, 32_768, false));

    /** Info for a model id; unknown ids get a conservative default. */
    public static ModelInfo of(String id) {
        if (id == null) {
            return fallback("unknown");
        }
        ModelInfo known = KNOWN.get(id.toLowerCase(Locale.ROOT));
        return known != null ? known : fallback(id);
    }

    private static ModelInfo fallback(String id) {
        return new ModelInfo(id, 32_768, 4_096, false);
    }
}
