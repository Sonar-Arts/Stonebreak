package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.joml.Vector3f;

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
                "bone_get_skeleton_info",
                "Get high-level info about the current skeleton (availability, bone count, currently selected bone id).",
                schema().build(),
                args -> editor.getSkeletonInfo()));

        registry.register(new McpTool(
                "bone_list",
                "List every bone in the current skeleton with id, name, parent, rest-pose transform, and resolved world head/tail positions.",
                schema().build(),
                args -> editor.listBones()));

        registry.register(new McpTool(
                "bone_get",
                "Get a single bone by id or name.",
                idOrNameSchema(),
                args -> editor.getBone(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "bone_get_selected",
                "Get the id of the currently-selected bone (null if none).",
                schema().build(),
                args -> editor.getSelectedBone().orElse(null)));

        registry.register(new McpTool(
                "bone_get_head_world",
                "Get the resolved world-space head/joint position of a bone.",
                idOrNameSchema(),
                args -> editor.getBoneHeadWorld(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "bone_get_tail_world",
                "Get the resolved world-space tail/endpoint position of a bone.",
                idOrNameSchema(),
                args -> editor.getBoneTailWorld(reqString(args, "id_or_name")).orElse(null)));

        // ---------- Mutate ----------

        registry.register(new McpTool(
                "bone_create",
                "Create a new bone. parent_bone_id may be omitted/null for a root bone. "
                        + "origin/pos/rot default to zero; endpoint defaults to (0,1,0) so the bone "
                        + "has a visible length out of the box. Rotation is Euler degrees (XYZ).",
                schema()
                        .str("name", "Display name for the new bone")
                        .str("parent_bone_id", "Parent bone id or name (omit for a root bone)")
                        .num("origin_x", "Pivot X").num("origin_y", "Pivot Y").num("origin_z", "Pivot Z")
                        .num("position_x", "Translation X").num("position_y", "Translation Y").num("position_z", "Translation Z")
                        .num("rotation_x", "Rotation X (degrees)").num("rotation_y", "Rotation Y (degrees)").num("rotation_z", "Rotation Z (degrees)")
                        .num("endpoint_x", "Tail offset X (local)").num("endpoint_y", "Tail offset Y (local)").num("endpoint_z", "Tail offset Z (local)")
                        .required("name")
                        .build(),
                args -> editor.createBone(
                        reqString(args, "name"),
                        optString(args, "parent_bone_id"),
                        optVec3(args, "origin_x", "origin_y", "origin_z"),
                        optVec3(args, "position_x", "position_y", "position_z"),
                        optVec3(args, "rotation_x", "rotation_y", "rotation_z"),
                        optVec3(args, "endpoint_x", "endpoint_y", "endpoint_z"))));

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
                "Set rest-pose components of a bone. Any axis triple (origin/position/rotation/endpoint) "
                        + "may be omitted to keep its current value. Rotation is Euler degrees (XYZ).",
                schema()
                        .str("id_or_name", "Bone id or name")
                        .num("origin_x", "Pivot X").num("origin_y", "Pivot Y").num("origin_z", "Pivot Z")
                        .num("position_x", "Translation X").num("position_y", "Translation Y").num("position_z", "Translation Z")
                        .num("rotation_x", "Rotation X (degrees)").num("rotation_y", "Rotation Y (degrees)").num("rotation_z", "Rotation Z (degrees)")
                        .num("endpoint_x", "Tail offset X").num("endpoint_y", "Tail offset Y").num("endpoint_z", "Tail offset Z")
                        .required("id_or_name")
                        .build(),
                args -> editor.setBoneTransform(
                        reqString(args, "id_or_name"),
                        optVec3(args, "origin_x", "origin_y", "origin_z"),
                        optVec3(args, "position_x", "position_y", "position_z"),
                        optVec3(args, "rotation_x", "rotation_y", "rotation_z"),
                        optVec3(args, "endpoint_x", "endpoint_y", "endpoint_z")).orElse(null)));

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

        // ---------- Undo / redo ----------

        registry.register(new McpTool(
                "bone_undo",
                "Undo the most recent bone create/delete/rename/transform/parent/clear operation.",
                schema().build(),
                args -> editor.undo()));

        registry.register(new McpTool(
                "bone_redo",
                "Redo the most recently undone bone operation.",
                schema().build(),
                args -> editor.redo()));

        registry.register(new McpTool(
                "bone_can_undo",
                "Returns true if there is at least one bone command in the undo stack.",
                schema().build(),
                args -> editor.canUndo()));

        registry.register(new McpTool(
                "bone_can_redo",
                "Returns true if there is at least one bone command in the redo stack.",
                schema().build(),
                args -> editor.canRedo()));
    }

    // ===================== Schema helpers =====================

    private SchemaBuilder schema() {
        return new SchemaBuilder(mapper);
    }

    private JsonNode idOrNameSchema() {
        return schema().str("id_or_name", "Bone id or name").required("id_or_name").build();
    }

    private static final class SchemaBuilder {
        private final ObjectMapper mapper;
        private final ObjectNode root;
        private final ObjectNode properties;
        private final ArrayNode required;

        SchemaBuilder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.root = mapper.createObjectNode();
            this.properties = mapper.createObjectNode();
            this.required = mapper.createArrayNode();
            root.put("type", "object");
            root.set("properties", properties);
        }

        SchemaBuilder str(String name, String description) { return prop(name, "string", description); }
        SchemaBuilder num(String name, String description) { return prop(name, "number", description); }
        SchemaBuilder bool(String name, String description) { return prop(name, "boolean", description); }

        SchemaBuilder prop(String name, String type, String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", type);
            def.put("description", description);
            properties.set(name, def);
            return this;
        }

        SchemaBuilder required(String... names) {
            for (String n : names) required.add(n);
            return this;
        }

        JsonNode build() {
            if (!required.isEmpty()) root.set("required", required);
            return root;
        }
    }

    // ===================== Arg parsing =====================

    private static String reqString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string argument: " + key);
        }
        return n.asText();
    }

    private static String optString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual()) return null;
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static float optFloat(JsonNode args, String key, float fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? fallback : n.floatValue();
    }

    private static Vector3f optVec3(JsonNode args, String xKey, String yKey, String zKey) {
        boolean any = args.has(xKey) || args.has(yKey) || args.has(zKey);
        if (!any) return null;
        return new Vector3f(
                optFloat(args, xKey, 0f),
                optFloat(args, yKey, 0f),
                optFloat(args, zKey, 0f));
    }
}
