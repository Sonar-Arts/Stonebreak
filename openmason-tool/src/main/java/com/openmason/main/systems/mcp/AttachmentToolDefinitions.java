package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.joml.Vector3f;

/**
 * Wires the {@link AttachmentEditingService} surface as MCP tools for the
 * Open Mason attachment point (socket) editor: create/delete sockets, edit
 * their transform (position + rotation + the scale applied to whatever model
 * is attached at runtime), bind them to a host part, selection, undo/redo.
 *
 * <p>Names are snake_case and prefixed with {@code attach_} so they don't
 * collide with the part- or bone-editing toolsets.
 */
public final class AttachmentToolDefinitions {

    private final AttachmentEditingService editor;
    private final ObjectMapper mapper;

    public AttachmentToolDefinitions(AttachmentEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "attach_get_info",
                "Get high-level info about the current attachment points / sockets "
                        + "(availability, socket count, currently selected socket id).",
                schema().build(),
                args -> editor.getAttachmentsInfo()));

        registry.register(new McpTool(
                "attach_list",
                "List every attachment point (socket) with id, name, host part binding, and its "
                        + "rest-pose-model-space position, rotation (Euler degrees), and scale. The scale "
                        + "is applied to the model attached to the socket at runtime.",
                schema().build(),
                args -> editor.listAttachments()));

        registry.register(new McpTool(
                "attach_get",
                "Get a single attachment point (socket) by id or name.",
                idOrNameSchema(),
                args -> editor.getAttachment(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "attach_get_selected",
                "Get the id of the currently-selected attachment point (null if none).",
                schema().build(),
                args -> editor.getSelectedAttachment().orElse(null)));

        // ---------- Mutate ----------

        registry.register(new McpTool(
                "attach_create",
                "Create a new attachment point (socket) — a named frame where another model mounts at "
                        + "runtime, following the host part through animation. parent_part (id or name) binds "
                        + "the socket to a part; omit it for a model-root socket. position/rotation are in "
                        + "rest-pose model space (position defaults to the host part's pivot, else origin); "
                        + "rotation is Euler degrees (XYZ), default zero. scale defaults to [1,1,1] and "
                        + "scales the ATTACHED model (e.g. [0.5,0.5,0.5] shrinks an accessory to half size).",
                schema()
                        .str("name", "Socket name — the runtime lookup key (e.g. \"face\")")
                        .str("parent_part", "Host part id or name (omit for a model-root socket)")
                        .vec3("position", "Position [x,y,z] in rest-pose model space")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z] applied to the attached model")
                        .required("name")
                        .build(),
                args -> editor.createAttachment(
                        reqString(args, "name"),
                        optString(args, "parent_part"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "scale"))));

        registry.register(new McpTool(
                "attach_delete",
                "Remove an attachment point (socket).",
                idOrNameSchema(),
                args -> editor.deleteAttachment(reqString(args, "id_or_name"))));

        registry.register(new McpTool(
                "attach_rename",
                "Rename an attachment point. Note the name is the runtime lookup key — in-game "
                        + "attachment calls reference sockets by name.",
                schema()
                        .str("id_or_name", "Socket id or current name")
                        .str("new_name", "New socket name")
                        .required("id_or_name", "new_name")
                        .build(),
                args -> editor.renameAttachment(reqString(args, "id_or_name"),
                        reqString(args, "new_name")).orElse(null)));

        registry.register(new McpTool(
                "attach_set_transform",
                "Set transform components of an attachment point. Any of position/rotation/scale may "
                        + "be omitted to keep its current value. position is rest-pose model space, rotation "
                        + "is Euler degrees (XYZ), scale multiplies the attached model's size ([1,1,1] = "
                        + "unchanged).",
                schema()
                        .str("id_or_name", "Socket id or name")
                        .vec3("position", "Position [x,y,z] in rest-pose model space")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z] applied to the attached model")
                        .required("id_or_name")
                        .build(),
                args -> editor.setAttachmentTransform(
                        reqString(args, "id_or_name"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "scale")).orElse(null)));

        registry.register(new McpTool(
                "attach_set_parent",
                "Re-bind an attachment point to a host part (id or name). Pass an empty/omitted "
                        + "parent_part to bind it to the model root. The binding controls which part the "
                        + "socket follows through animation at runtime; it does not move the socket.",
                schema()
                        .str("id_or_name", "Socket id or name")
                        .str("parent_part", "Host part id or name (empty/omit for model root)")
                        .required("id_or_name")
                        .build(),
                args -> editor.setAttachmentParent(
                        reqString(args, "id_or_name"),
                        optString(args, "parent_part")).orElse(null)));

        registry.register(new McpTool(
                "attach_select",
                "Set the currently-selected attachment point (drives the viewport gizmo and inspector). "
                        + "Pass an empty/omitted id_or_name to clear selection.",
                schema()
                        .str("id_or_name", "Socket id or name (empty/omit clears selection)")
                        .build(),
                args -> editor.selectAttachment(optString(args, "id_or_name"))));

        registry.register(new McpTool(
                "attach_clear",
                "Remove every attachment point from the model.",
                schema().build(),
                args -> editor.clearAttachments()));

        // ---------- Undo / redo ----------

        registry.register(new McpTool(
                "attach_undo",
                "Undo the most recent socket create/delete/rename/transform/parent/clear operation.",
                schema().build(),
                args -> editor.undo()));

        registry.register(new McpTool(
                "attach_redo",
                "Redo the most recently undone socket operation.",
                schema().build(),
                args -> editor.redo()));
    }

    // ===================== Schema helpers =====================

    private SchemaBuilder schema() {
        return new SchemaBuilder(mapper);
    }

    private JsonNode idOrNameSchema() {
        return schema().str("id_or_name", "Socket id or name").required("id_or_name").build();
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

        /** Fixed-length [x,y,z] number array. */
        SchemaBuilder vec3(String name, String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", "array");
            def.put("description", description);
            ObjectNode items = mapper.createObjectNode();
            items.put("type", "number");
            def.set("items", items);
            def.put("minItems", 3);
            def.put("maxItems", 3);
            properties.set(name, def);
            return this;
        }

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

    /** Parse an optional [x,y,z] array argument; null when absent. */
    private static Vector3f optVec3(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isArray() || n.size() != 3
                || !n.get(0).isNumber() || !n.get(1).isNumber() || !n.get(2).isNumber()) {
            throw new IllegalArgumentException(key + " must be a [x,y,z] number array");
        }
        return new Vector3f(n.get(0).floatValue(), n.get(1).floatValue(), n.get(2).floatValue());
    }
}
