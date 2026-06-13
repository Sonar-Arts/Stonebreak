package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.joml.Vector3f;

/**
 * Wires the {@link ModelEditingService} surface as MCP tools.
 *
 * <p>All schemas are JSON Schema draft-07 (the MCP-recommended dialect).
 * Names are snake_case to match common MCP conventions.
 */
public final class OpenMasonToolDefinitions {

    private final ModelEditingService editor;
    private final ObjectMapper mapper;

    public OpenMasonToolDefinitions(ModelEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "get_model_info",
                "Get high-level info about the currently loaded model (whether one is loaded and total part count).",
                schema().build(),
                args -> editor.getModelInfo()));

        registry.register(new McpTool(
                "list_parts",
                "List all parts in the current model, including id, name, transform, visibility, lock state, and vertex/triangle counts.",
                schema().build(),
                args -> editor.listParts()));

        registry.register(new McpTool(
                "get_part",
                "Get a single part by id or name.",
                schema().str("id_or_name", "Part id (UUID) or display name").required("id_or_name").build(),
                args -> editor.getPart(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "inspect_part",
                "Deep debug inspection of a single part: descriptor, mesh range, local bounding box, "
                        + "and transform matrix (4x4 row-major, 16 floats). Optional flags include the raw "
                        + "vertex/texCoord/index/face-mapping arrays — leave them off (default) to keep responses small.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("include_vertices", "Include raw interleaved vertex positions (xyz)")
                        .bool("include_tex_coords", "Include raw interleaved texture coords (uv)")
                        .bool("include_indices", "Include raw triangle index buffer")
                        .bool("include_face_mapping", "Include triangle→face id mapping")
                        .required("id_or_name")
                        .build(),
                args -> editor.inspectPart(
                        reqString(args, "id_or_name"),
                        optBool(args, "include_vertices", false),
                        optBool(args, "include_tex_coords", false),
                        optBool(args, "include_indices", false),
                        optBool(args, "include_face_mapping", false)).orElse(null)));

        registry.register(new McpTool(
                "get_selection",
                "Get the set of currently selected part ids.",
                schema().build(),
                args -> editor.getSelection()));

        // ---------- Mutate: parts ----------

        registry.register(new McpTool(
                "create_part",
                "Spawn a new part with a primitive shape (CUBE, PYRAMID, PANE, SPRITE) at the model origin. Auto-creates a blank model if none is loaded.",
                schema()
                        .str("shape", "One of: CUBE, PYRAMID, PANE, SPRITE")
                        .str("name", "Display name for the new part")
                        .vec3("size", "[x,y,z] size (default [1,1,1])")
                        .required("shape", "name")
                        .build(),
                args -> {
                    Vector3f size = optVec3(args, "size");
                    if (size == null) size = new Vector3f(1.0f);
                    return editor.createPart(reqString(args, "shape"), reqString(args, "name"), size);
                }));

        registry.register(new McpTool(
                "delete_part",
                "Remove a part from the model.",
                idOrNameSchema(),
                args -> editor.deletePart(reqString(args, "id_or_name"))));

        registry.register(new McpTool(
                "duplicate_part",
                "Duplicate an existing part (geometry + transform). Returns the new part.",
                idOrNameSchema(),
                args -> editor.duplicatePart(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "rename_part",
                "Rename a part.",
                schema()
                        .str("id_or_name", "Part id or current name")
                        .str("new_name", "New display name")
                        .required("id_or_name", "new_name")
                        .build(),
                args -> editor.renamePart(reqString(args, "id_or_name"), reqString(args, "new_name"))
                        .orElse(null)));

        registry.register(new McpTool(
                "set_part_transform",
                "Set the local transform for a part. Any of origin/position/rotation/scale may be omitted to keep its current value. Rotation is Euler degrees.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .vec3("origin", "Pivot [x,y,z]")
                        .vec3("position", "Translation [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z]")
                        .required("id_or_name")
                        .build(),
                args -> editor.setPartTransform(
                        reqString(args, "id_or_name"),
                        optVec3(args, "origin"), optVec3(args, "position"),
                        optVec3(args, "rotation"), optVec3(args, "scale")).orElse(null)));

        registry.register(new McpTool(
                "translate_part",
                "Translate a part by a delta offset (added to current position).",
                deltaSchema("Delta [x,y,z]"),
                args -> editor.translatePart(
                        reqString(args, "id_or_name"), reqVec3(args, "delta")).orElse(null)));

        registry.register(new McpTool(
                "rotate_part",
                "Rotate a part by Euler degree deltas (added to current rotation).",
                deltaSchema("Euler degree deltas [x,y,z]"),
                args -> editor.rotatePart(
                        reqString(args, "id_or_name"), reqVec3(args, "delta")).orElse(null)));

        registry.register(new McpTool(
                "scale_part",
                "Multiply a part's scale by per-axis factors.",
                deltaSchema("Per-axis factors [x,y,z]"),
                args -> editor.scalePart(
                        reqString(args, "id_or_name"), reqVec3(args, "delta")).orElse(null)));

        registry.register(new McpTool(
                "set_part_visibility",
                "Show or hide a part.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("visible", "true to show, false to hide")
                        .required("id_or_name", "visible")
                        .build(),
                args -> editor.setPartVisibility(
                        reqString(args, "id_or_name"), args.get("visible").asBoolean()).orElse(null)));

        registry.register(new McpTool(
                "select_part",
                "Select a part. Pass additive=true to add to the current selection (default replaces it).",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("additive", "Add to existing selection (default false)")
                        .required("id_or_name")
                        .build(),
                args -> editor.selectPart(
                        reqString(args, "id_or_name"),
                        args.has("additive") && args.get("additive").asBoolean(false))));

        registry.register(new McpTool(
                "focus_camera_on",
                "Select a part as the camera focus target. (Camera-framing follows whatever the editor's selection-focus binding does.)",
                idOrNameSchema(),
                args -> editor.focusCameraOn(reqString(args, "id_or_name"))));

        // ---------- Mutate: mesh elements ----------

        registry.register(new McpTool(
                "list_part_vertices",
                "Get a part's vertices as {count, positions} where positions is a flat [x,y,z, ...] array; "
                        + "vertex i is at positions[3i]. Local indices are stable across structural edits; "
                        + "use them with move_vertex.",
                idOrNameSchema(),
                args -> editor.listPartVertices(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "list_part_edges",
                "Get a part's unique edges as {count, vertexPairs} where vertexPairs is a flat "
                        + "[a,b, a,b, ...] array of part-local vertex indices; edge i is at vertexPairs[2i]. "
                        + "Ordering is stable for a given mesh topology.",
                idOrNameSchema(),
                args -> editor.listPartEdges(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "list_part_faces",
                "List the logical faces of a part as {faceId, vertices} entries (part-local vertex indices). "
                        + "A face groups one or more triangles sharing a face id.",
                idOrNameSchema(),
                args -> editor.listPartFaces(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "move_vertex",
                "Move a single vertex by part-local index. xyz is a delta by default; "
                        + "set absolute=true to treat it as the new position.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .num("local_index", "Part-local vertex index from list_part_vertices")
                        .vec3("xyz", "Delta (default) or absolute position [x,y,z]")
                        .bool("absolute", "Treat xyz as the new position (default false: delta)")
                        .required("id_or_name", "local_index", "xyz")
                        .build(),
                args -> editor.moveVertex(
                        reqString(args, "id_or_name"),
                        (int) reqFloat(args, "local_index"),
                        reqVec3(args, "xyz"),
                        optBool(args, "absolute", false)).orElse(null)));

        registry.register(new McpTool(
                "move_edge",
                "Translate both endpoints of an edge by a delta. Edge index comes from list_part_edges.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .num("edge_index", "Edge index from list_part_edges")
                        .vec3("delta", "Delta [x,y,z]")
                        .required("id_or_name", "edge_index", "delta")
                        .build(),
                args -> editor.moveEdge(
                        reqString(args, "id_or_name"),
                        (int) reqFloat(args, "edge_index"),
                        reqVec3(args, "delta")).orElse(null)));

        registry.register(new McpTool(
                "set_part_geometry",
                "Replace a part's geometry wholesale with caller-supplied vertex/index/face data. "
                        + "Topology, vertex count, and face count may all change; the part's transform is preserved. "
                        + "Positions are part-local. vertices is a flat [x,y,z, x,y,z, ...] array. "
                        + "indices is a flat triangle index array (length divisible by 3). "
                        + "tex_coords (optional) is [u,v, u,v, ...] matching vertex count; defaults to zeros. "
                        + "triangle_to_face_id (optional) gives one face id per triangle; defaults to one face per triangle.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .arr("vertices", "number", "Flat [x,y,z, x,y,z, ...] vertex positions in part-local space")
                        .arr("indices", "number", "Flat triangle index array; length must be divisible by 3")
                        .arr("tex_coords", "number", "Optional flat [u,v, u,v, ...] UVs; length must be 2 × vertex count if supplied")
                        .arr("triangle_to_face_id", "number", "Optional one face id per triangle; length must equal triangle count if supplied")
                        .required("id_or_name", "vertices", "indices")
                        .build(),
                args -> editor.setPartGeometry(
                        reqString(args, "id_or_name"),
                        optFloatArray(args, "vertices"),
                        optIntArray(args, "indices"),
                        optFloatArray(args, "tex_coords"),
                        optIntArray(args, "triangle_to_face_id")).orElse(null)));

        registry.register(new McpTool(
                "move_face",
                "Translate every unique vertex of a face by a delta. Face id comes from list_part_faces.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .num("local_face_id", "Local face id from list_part_faces")
                        .vec3("delta", "Delta [x,y,z]")
                        .required("id_or_name", "local_face_id", "delta")
                        .build(),
                args -> editor.moveFace(
                        reqString(args, "id_or_name"),
                        (int) reqFloat(args, "local_face_id"),
                        reqVec3(args, "delta")).orElse(null)));

        // ---------- Undo / redo (model + face-texture share this history) ----------

        registry.register(new McpTool(
                "model_undo",
                "Undo the most recent model or face-texture mutation. Covers part/geometry "
                        + "operations and per-face texture commits/creates/resizes from the MCP "
                        + "surface plus interactive UI edits in the viewport.",
                schema().build(),
                args -> editor.undo()));

        registry.register(new McpTool(
                "model_redo",
                "Redo the most recently undone model or face-texture mutation.",
                schema().build(),
                args -> editor.redo()));
    }

    // ===================== Schema builder =====================

    private SchemaBuilder schema() {
        return new SchemaBuilder(mapper);
    }

    private JsonNode idOrNameSchema() {
        return schema().str("id_or_name", "Part id or name").required("id_or_name").build();
    }

    private JsonNode deltaSchema(String deltaDescription) {
        return schema()
                .str("id_or_name", "Part id or name")
                .vec3("delta", deltaDescription)
                .required("id_or_name", "delta")
                .build();
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

        SchemaBuilder str(String name, String description) {
            return prop(name, "string", description);
        }

        SchemaBuilder num(String name, String description) {
            return prop(name, "number", description);
        }

        SchemaBuilder bool(String name, String description) {
            return prop(name, "boolean", description);
        }

        SchemaBuilder arr(String name, String itemType, String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", "array");
            def.put("description", description);
            ObjectNode items = mapper.createObjectNode();
            items.put("type", itemType);
            def.set("items", items);
            properties.set(name, def);
            return this;
        }

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
            if (!required.isEmpty()) {
                root.set("required", required);
            }
            return root;
        }
    }

    // ===================== Argument parsing =====================

    private static String reqString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string argument: " + key);
        }
        return n.asText();
    }

    private static float reqFloat(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing required numeric argument: " + key);
        }
        return n.floatValue();
    }

    private static boolean optBool(JsonNode args, String key, boolean fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isBoolean()) ? fallback : n.asBoolean();
    }

    private static float[] optFloatArray(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isArray()) return null;
        float[] out = new float[n.size()];
        for (int i = 0; i < n.size(); i++) {
            JsonNode item = n.get(i);
            if (item == null || !item.isNumber()) {
                throw new IllegalArgumentException(key + "[" + i + "] is not a number");
            }
            out[i] = item.floatValue();
        }
        return out;
    }

    private static int[] optIntArray(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isArray()) return null;
        int[] out = new int[n.size()];
        for (int i = 0; i < n.size(); i++) {
            JsonNode item = n.get(i);
            if (item == null || !item.isNumber()) {
                throw new IllegalArgumentException(key + "[" + i + "] is not a number");
            }
            out[i] = item.intValue();
        }
        return out;
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

    private static Vector3f reqVec3(JsonNode args, String key) {
        Vector3f v = optVec3(args, key);
        if (v == null) throw new IllegalArgumentException("Missing required [x,y,z] argument: " + key);
        return v;
    }
}
