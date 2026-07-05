package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.optVec3;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * Wires the {@link BoneEditingService} surface as MCP tools for the
 * Open Mason skeleton editor (bone create/delete, transform edits,
 * parenting, selection, world-pose queries).
 *
 * <p>Names are snake_case and prefixed with {@code bone_} so they don't
 * collide with the part-editing toolset.
 */
public final class BoneToolDefinitions {

    private final BoneEditingService editor;
    private final ObjectMapper mapper;

    public BoneToolDefinitions(BoneEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "bone_list",
                "List every bone: id, name, parent, rest-pose transform, resolved world head/tail. "
                        + "(model_summary has the compact skeleton overview.)",
                schema().build(),
                args -> editor.listBones()));

        registry.register(new McpTool(
                "bone_get",
                "Get a single bone by id or name (includes resolved world head/tail).",
                idOrNameSchema(),
                args -> editor.getBone(reqString(args, "id_or_name")).orElse(null)));

        // ---------- Mutate ----------

        registry.register(new McpTool(
                "bone_create",
                "Create a new bone. parent_bone_id may be omitted/null for a root bone. "
                        + "origin/position/rotation default to zero; endpoint defaults to [0,1,0] so the bone "
                        + "has a visible length out of the box. Rotation is Euler degrees (XYZ).",
                schema()
                        .str("name", "Display name for the new bone")
                        .str("parent_bone_id", "Parent bone id or name (omit for a root bone)")
                        .vec3("origin", "Pivot [x,y,z]")
                        .vec3("position", "Translation [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("endpoint", "Tail offset [x,y,z] (local)")
                        .required("name")
                        .build(),
                args -> editor.createBone(
                        reqString(args, "name"),
                        optString(args, "parent_bone_id"),
                        optVec3(args, "origin"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "endpoint"))));

        registry.register(new McpTool(
                "bone_delete",
                "Remove a bone. Any bones whose parent was this bone become roots.",
                idOrNameSchema(),
                args -> editor.deleteBone(reqString(args, "id_or_name"))));

        registry.register(new McpTool(
                "bone_rename",
                "Rename a bone.",
                schema()
                        .str("id_or_name", "Bone id or current name")
                        .str("new_name", "New display name")
                        .required("id_or_name", "new_name")
                        .build(),
                args -> editor.renameBone(reqString(args, "id_or_name"), reqString(args, "new_name"))
                        .orElse(null)));

        registry.register(new McpTool(
                "bone_set_transform",
                "Set rest-pose components of a bone. Any of origin/position/rotation/endpoint "
                        + "may be omitted to keep its current value. Rotation is Euler degrees (XYZ).",
                schema()
                        .str("id_or_name", "Bone id or name")
                        .vec3("origin", "Pivot [x,y,z]")
                        .vec3("position", "Translation [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("endpoint", "Tail offset [x,y,z]")
                        .required("id_or_name")
                        .build(),
                args -> editor.setBoneTransform(
                        reqString(args, "id_or_name"),
                        optVec3(args, "origin"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "endpoint")).orElse(null)));

        registry.register(new McpTool(
                "bone_set_parent",
                "Re-parent a bone. Pass an empty/null parent_id_or_name to make the bone a root.",
                schema()
                        .str("id_or_name", "Bone id or name")
                        .str("parent_id_or_name", "New parent bone id or name (empty/omit for root)")
                        .required("id_or_name")
                        .build(),
                args -> editor.setBoneParent(
                        reqString(args, "id_or_name"),
                        optString(args, "parent_id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "bone_select",
                "Set the currently-selected bone. Pass an empty/null id_or_name to clear selection.",
                schema()
                        .str("id_or_name", "Bone id or name (empty/omit clears selection)")
                        .build(),
                args -> editor.selectBone(optString(args, "id_or_name"))));

        registry.register(new McpTool(
                "bone_clear",
                "Remove every bone from the skeleton.",
                schema().build(),
                args -> editor.clearBones()));

    }

    // ===================== Schema helpers =====================

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private JsonNode idOrNameSchema() {
        return schema().str("id_or_name", "Bone id or name").required("id_or_name").build();
    }
}
