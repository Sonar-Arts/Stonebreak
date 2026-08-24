package com.openmason.main.systems.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds the assistant system prompt: the static core from
 * {@code /assistant/system-prompt.md} plus a small dynamic block (model,
 * capabilities, user extras). Rebuilt every send so settings changes apply
 * immediately.
 */
public final class SystemPromptBuilder {

    private static volatile String core;

    private SystemPromptBuilder() {
    }

    public static String build(String modelId, String projectName, boolean libalexAvailable,
                               boolean pythonAvailable, String userExtras) {
        StringBuilder sb = new StringBuilder(core());
        sb.append("\n\n## Session\n");
        sb.append("- Model: ").append(modelId == null ? "unknown" : modelId).append('\n');
        if (projectName != null && !projectName.isBlank()) {
            sb.append("- Open model/project: ").append(projectName).append('\n');
        }
        sb.append("- knowledge_search (libalex): ")
                .append(libalexAvailable ? "available" : "not available").append('\n');
        if (!pythonAvailable) {
            sb.append("- run_python_script is UNAVAILABLE this session (GraalPy missing) — "
                    + "use run_model_ops JSON batches instead\n");
        }
        if (userExtras != null && !userExtras.isBlank()) {
            sb.append("\n## User instructions\n").append(userExtras.strip()).append('\n');
        }
        return sb.toString();
    }

    private static String core() {
        String cached = core;
        if (cached != null) {
            return cached;
        }
        try (InputStream in = SystemPromptBuilder.class
                .getResourceAsStream("/assistant/system-prompt.md")) {
            if (in == null) {
                throw new IllegalStateException("missing /assistant/system-prompt.md");
            }
            cached = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            core = cached;
            return cached;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read system prompt", e);
        }
    }
}
