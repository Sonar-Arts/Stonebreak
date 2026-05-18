package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers MCP tools for direct per-face texture editing on the loaded model,
 * exposing {@link FaceTextureEditingService}.
 *
 * <p>Distinguished from the {@code tex_*} surface (which requires the texture
 * editor to be open and a face region active) by the {@code model_face_*} prefix
 * — these tools operate directly on each face's GPU texture in a session-based
 * read/edit/commit cycle.
 */
public final class FaceTextureToolDefinitions {

    private final FaceTextureEditingService editor;
    private final ObjectMapper mapper;

    public FaceTextureToolDefinitions(FaceTextureEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "model_face_list_textures",
                "List every face of the loaded model with its texture metadata: faceId, "
                        + "materialId, material name, GPU texture id and dimensions, vertex count, "
                        + "face normal (x,y,z), orientation label (+X/-X/+Y/-Y/+Z/-Z/OBLIQUE), "
                        + "UV rotation degrees, UV region (u0,v0,u1,v1), owning part name, "
                        + "suggestedWidth/suggestedHeight (geometry-derived size at the editor's "
                        + "default pixels-per-unit — use this as a reference for sizing new "
                        + "per-face textures), and autoResize (whether the editor may rescale "
                        + "this face's texture on geometry changes; set to false after "
                        + "model_face_resize_texture or model_face_create_texture).",
                schema().build(),
                args -> editor.listFaceTextures()));

        registry.register(new McpTool(
                "model_face_get_info",
                "Get texture metadata for a single face (same fields as model_face_list_textures).",
                schema().intg("face_id", "Face identifier").required("face_id").build(),
                args -> editor.getFaceInfo(reqInt(args, "face_id"))));

        // ---------- Session lifecycle ----------

        registry.register(new McpTool(
                "model_face_open",
                "Open an editing session for a face: reads the face's GPU texture pixels into an "
                        + "in-memory canvas so subsequent draw tools can mutate it. Multiple sessions "
                        + "may be open concurrently. Must be followed by model_face_commit (to upload) "
                        + "or model_face_discard (to drop).",
                schema().intg("face_id", "Face identifier").required("face_id").build(),
                args -> editor.openSession(reqInt(args, "face_id"))));

        registry.register(new McpTool(
                "model_face_commit",
                "Commit an open session: uploads the dirty region of the in-memory canvas back to "
                        + "the GPU texture via glTexSubImage2D, then closes the session.",
                schema().intg("face_id", "Face identifier").required("face_id").build(),
                args -> editor.commitSession(reqInt(args, "face_id"))));

        registry.register(new McpTool(
                "model_face_discard",
                "Discard an open session without uploading any edits. Returns true if a session was closed.",
                schema().intg("face_id", "Face identifier").required("face_id").build(),
                args -> editor.discardSession(reqInt(args, "face_id"))));

        registry.register(new McpTool(
                "model_face_list_sessions",
                "List face IDs that currently have an open editing session.",
                schema().build(),
                args -> editor.listOpenSessions()));

        // ---------- Pixel read ----------

        registry.register(new McpTool(
                "model_face_get_pixel",
                "Read a single RGBA pixel from a face's open session canvas.",
                schema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Pixel X")
                        .intg("y", "Pixel Y")
                        .required("face_id", "x", "y")
                        .build(),
                args -> editor.getPixel(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"))));

        // ---------- Pixel mutations (require open session) ----------

        registry.register(new McpTool(
                "model_face_set_pixel",
                "Set a single pixel on a face's open session canvas. Channel values 0-255.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Pixel X").intg("y", "Pixel Y")
                        .required("face_id", "x", "y", "r", "g", "b", "a")
                        .build(),
                args -> editor.setPixel(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "model_face_set_pixels",
                "Bulk per-pixel write to a face's open session canvas. 'pixels' is an array of "
                        + "{x,y,r,g,b,a} entries.",
                pixelsArraySchema(),
                args -> editor.setPixels(reqInt(args, "face_id"), parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "model_face_fill",
                "Fill every pixel of a face's open session canvas with a solid RGBA color.",
                rgbaSchema().intg("face_id", "Face identifier")
                        .required("face_id", "r", "g", "b", "a").build(),
                args -> editor.fillFace(reqInt(args, "face_id"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "model_face_clear",
                "Clear the open session canvas of a face to transparent (RGBA 0,0,0,0).",
                schema().intg("face_id", "Face identifier").required("face_id").build(),
                args -> editor.clearFace(reqInt(args, "face_id"))));

        registry.register(new McpTool(
                "model_face_fill_rect",
                "Fill a solid rectangle on a face's open session canvas.",
                rectSchema().intg("face_id", "Face identifier")
                        .required("face_id", "x", "y", "w", "h", "r", "g", "b", "a").build(),
                args -> editor.fillRect(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "model_face_draw_rect",
                "Draw a rectangle outline (1px thick) on a face's open session canvas.",
                rectSchema().intg("face_id", "Face identifier")
                        .required("face_id", "x", "y", "w", "h", "r", "g", "b", "a").build(),
                args -> editor.drawRect(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "model_face_draw_line",
                "Draw a 1-pixel Bresenham line between (x0,y0) and (x1,y1) on a face's open session canvas.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("x0", "Start X").intg("y0", "Start Y")
                        .intg("x1", "End X").intg("y1", "End Y")
                        .required("face_id", "x0", "y0", "x1", "y1", "r", "g", "b", "a")
                        .build(),
                args -> editor.drawLine(reqInt(args, "face_id"),
                        reqInt(args, "x0"), reqInt(args, "y0"),
                        reqInt(args, "x1"), reqInt(args, "y1"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "model_face_flood_fill",
                "4-connected flood fill starting at (x,y) on a face's open session canvas.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Seed X").intg("y", "Seed Y")
                        .required("face_id", "x", "y", "r", "g", "b", "a")
                        .build(),
                args -> editor.floodFill(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        // ---------- Create per-face texture ----------

        registry.register(new McpTool(
                "model_face_create_texture",
                "Allocate a new material + GPU texture for a face that currently has no explicit "
                        + "per-face mapping (uses default material). Creates a blank canvas filled with "
                        + "the given RGBA color and assigns it to the face, making the face addressable "
                        + "via model_face_open and friends. Width/height in [1,1024]. Sets autoResize "
                        + "to false for this face — the caller's chosen size is preserved across "
                        + "geometry edits. The response includes suggestedWidth/suggestedHeight so the "
                        + "caller can compare its chosen size against the geometry-derived size; query "
                        + "model_face_get_info first if unsure what dimensions to use.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("width", "Initial texture width in pixels")
                        .intg("height", "Initial texture height in pixels")
                        .required("face_id", "width", "height", "r", "g", "b", "a")
                        .build(),
                args -> editor.createFaceTexture(reqInt(args, "face_id"),
                        reqInt(args, "width"), reqInt(args, "height"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        // ---------- Resize ----------

        registry.register(new McpTool(
                "model_face_resize_texture",
                "Resize a face's GPU texture (nearest-neighbor). Allocates a new GL texture and "
                        + "swaps it into the face's material — any other face sharing the material is "
                        + "also affected. Drops any open session for this face. Width/height in [1,1024]. "
                        + "Sets autoResize to false for this face so the editor will not silently "
                        + "rescale the texture when face geometry later changes.",
                schema()
                        .intg("face_id", "Face identifier")
                        .intg("width", "New width in pixels")
                        .intg("height", "New height in pixels")
                        .required("face_id", "width", "height")
                        .build(),
                args -> editor.resize(reqInt(args, "face_id"),
                        reqInt(args, "width"), reqInt(args, "height"))));
    }

    // ===================== Schema helpers =====================

    private SchemaBuilder schema() {
        return new SchemaBuilder(mapper);
    }

    private SchemaBuilder rgbaSchema() {
        return schema()
                .intg("r", "Red 0..255")
                .intg("g", "Green 0..255")
                .intg("b", "Blue 0..255")
                .intg("a", "Alpha 0..255");
    }

    private SchemaBuilder rectSchema() {
        return rgbaSchema()
                .intg("x", "Origin X")
                .intg("y", "Origin Y")
                .intg("w", "Width in pixels")
                .intg("h", "Height in pixels");
    }

    private JsonNode pixelsArraySchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();

        ObjectNode faceIdNode = mapper.createObjectNode();
        faceIdNode.put("type", "integer");
        faceIdNode.put("description", "Face identifier");
        props.set("face_id", faceIdNode);

        ObjectNode arr = mapper.createObjectNode();
        arr.put("type", "array");
        arr.put("description", "Array of pixel entries");

        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        ObjectNode itemProps = mapper.createObjectNode();
        for (String n : new String[]{"x", "y", "r", "g", "b", "a"}) {
            ObjectNode field = mapper.createObjectNode();
            field.put("type", "integer");
            itemProps.set(n, field);
        }
        item.set("properties", itemProps);
        ArrayNode itemRequired = mapper.createArrayNode();
        for (String n : new String[]{"x", "y", "r", "g", "b", "a"}) itemRequired.add(n);
        item.set("required", itemRequired);

        arr.set("items", item);
        props.set("pixels", arr);
        root.set("properties", props);

        ArrayNode required = mapper.createArrayNode();
        required.add("face_id");
        required.add("pixels");
        root.set("required", required);
        return root;
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

        SchemaBuilder intg(String name, String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", "integer");
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

    // ===================== Argument parsing =====================

    private static List<FaceTextureEditingService.PixelEntry> parsePixels(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("Missing required array argument: pixels");
        }
        List<FaceTextureEditingService.PixelEntry> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode p = arr.get(i);
            out.add(new FaceTextureEditingService.PixelEntry(
                    reqInt(p, "x"), reqInt(p, "y"),
                    reqInt(p, "r"), reqInt(p, "g"),
                    reqInt(p, "b"), reqInt(p, "a")));
        }
        return out;
    }

    private static int reqInt(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing required integer argument: " + key);
        }
        return n.intValue();
    }
}
