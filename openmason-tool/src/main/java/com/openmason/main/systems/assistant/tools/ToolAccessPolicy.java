package com.openmason.main.systems.assistant.tools;

import java.util.Set;

/**
 * Classifies MCP tools into {@link ToolCategory}s for the assistant's
 * approval gate. One file on purpose: adding a tool that needs user
 * authorization is a one-line change here.
 *
 * <p>Unknown tools default to MUTATING (safe): they get whatever policy the
 * user set for mutations, never silent auto-read treatment.
 */
public final class ToolAccessPolicy {

    /** Tools that replace the user's working set → REQUIRES_AUTH. */
    private static final Set<String> REQUIRES_AUTH = Set.of(
            "asset_open");

    /** Read-only tools that name-heuristics miss. */
    private static final Set<String> READ_ONLY_EXPLICIT = Set.of(
            "model_summary", "describe_api", "knowledge", "knowledge_search",
            "viewport_capture", "canvas_capture", "part_mesh", "tex_editor_status",
            "validate_model_ops", "script_list", "script_read",
            "asset_list", "asset_manifest", "asset_mesh_summary", "asset_face_data",
            "asset_texture_describe", "asset_texture_export", "asset_check_winding",
            "model_describe", "model_check_winding");

    private static final String[] READ_ONLY_PREFIXES = {
            "get_", "list_", "inspect_", "tex_describe", "tex_get_", "tex_list_",
            "model_face_describe", "model_face_get_", "model_face_list_",
            "anim_get_", "anim_list_", "bone_get", "bone_list",
            "attach_get", "attach_list",
    };

    private ToolAccessPolicy() {
    }

    public static ToolCategory categorize(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolCategory.MUTATING;
        }
        if (REQUIRES_AUTH.contains(toolName)) {
            return ToolCategory.REQUIRES_AUTH;
        }
        if (READ_ONLY_EXPLICIT.contains(toolName)) {
            return ToolCategory.READ_ONLY;
        }
        for (String prefix : READ_ONLY_PREFIXES) {
            if (toolName.startsWith(prefix)) {
                return ToolCategory.READ_ONLY;
            }
        }
        return ToolCategory.MUTATING;
    }
}
