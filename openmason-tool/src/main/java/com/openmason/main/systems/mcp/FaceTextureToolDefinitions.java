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
                "List every face of the loaded model with texture metadata: faceId, material id/name, "
                        + "GPU texture id + dimensions, vertex count, normal [x,y,z], orientation label "
                        + "(+X/-X/+Y/-Y/+Z/-Z/OBLIQUE), UV rotation, UV region [u0,v0,u1,v1], part name, "
                        + "suggested width/height (geometry-derived reference size for new textures), and "
                        + "autoResize (false once a size was set explicitly).",
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

        registry.register(new McpTool(
                "model_face_get_region",
                "Read a rectangular region of a face's open session canvas as a flat [r,g,b,a, ...] "
                        + "array, row-major from (x,y). Far cheaper than per-pixel model_face_get_pixel calls.",
                schema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Origin X").intg("y", "Origin Y")
                        .intg("w", "Width in pixels").intg("h", "Height in pixels")
                        .required("face_id", "x", "y", "w", "h")
                        .build(),
                args -> editor.getRegion(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"))));

        // ---------- Pixel mutations (require open session) ----------

        registry.register(new McpTool(
                "model_face_set_pixel",
                "Set a single pixel on a face's open session canvas.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Pixel X").intg("y", "Pixel Y")
                        .required("face_id", "x", "y", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.setPixel(reqInt(args, "face_id"),
                            reqInt(args, "x"), reqInt(args, "y"), c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "model_face_set_pixels",
                "Bulk per-pixel write to a face's open session canvas. 'pixels' is a flat int array "
                        + "[x,y,r,g,b,a, x,y,r,g,b,a, ...] (6 values per pixel).",
                pixelsArraySchema(),
                args -> editor.setPixels(reqInt(args, "face_id"), parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "model_face_fill",
                "Fill every pixel of a face's open session canvas with a solid RGBA color.",
                rgbaSchema().intg("face_id", "Face identifier")
                        .required("face_id", "color").build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.fillFace(reqInt(args, "face_id"), c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "model_face_clear",
                "Clear the open session canvas of a face to transparent (RGBA 0,0,0,0).",
                schema().intg("face_id", "Face identifier").required("face_id").build(),
                args -> editor.clearFace(reqInt(args, "face_id"))));

        registry.register(new McpTool(
                "model_face_fill_rect",
                "Fill a solid rectangle on a face's open session canvas.",
                rectSchema().intg("face_id", "Face identifier")
                        .required("face_id", "x", "y", "w", "h", "color").build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.fillRect(reqInt(args, "face_id"),
                            reqInt(args, "x"), reqInt(args, "y"),
                            reqInt(args, "w"), reqInt(args, "h"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "model_face_draw_rect",
                "Draw a rectangle outline (1px thick) on a face's open session canvas.",
                rectSchema().intg("face_id", "Face identifier")
                        .required("face_id", "x", "y", "w", "h", "color").build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.drawRect(reqInt(args, "face_id"),
                            reqInt(args, "x"), reqInt(args, "y"),
                            reqInt(args, "w"), reqInt(args, "h"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "model_face_draw_line",
                "Draw a 1-pixel Bresenham line between (x0,y0) and (x1,y1) on a face's open session canvas.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("x0", "Start X").intg("y0", "Start Y")
                        .intg("x1", "End X").intg("y1", "End Y")
                        .required("face_id", "x0", "y0", "x1", "y1", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.drawLine(reqInt(args, "face_id"),
                            reqInt(args, "x0"), reqInt(args, "y0"),
                            reqInt(args, "x1"), reqInt(args, "y1"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "model_face_flood_fill",
                "4-connected flood fill starting at (x,y) on a face's open session canvas.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Seed X").intg("y", "Seed Y")
                        .required("face_id", "x", "y", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.floodFill(reqInt(args, "face_id"),
                            reqInt(args, "x"), reqInt(args, "y"),
                            c[0], c[1], c[2], c[3]);
                }));

        // ---------- Create per-face texture ----------

        registry.register(new McpTool(
                "model_face_create_texture",
                "Allocate a new material + GPU texture (filled with the given color) for a face that "
                        + "has no per-face mapping yet, making it addressable via model_face_open. "
                        + "Width/height in [1,1024]; sets autoResize=false so the chosen size survives "
                        + "geometry edits. Response includes the geometry-suggested size for comparison; "
                        + "query model_face_get_info first if unsure what dimensions to use. "
                        + "To texture many faces, use model_face_create_textures instead — one call is "
                        + "safer and faster than looping this one.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intg("width", "Initial texture width in pixels")
                        .intg("height", "Initial texture height in pixels")
                        .required("face_id", "width", "height", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.createFaceTexture(reqInt(args, "face_id"),
                            reqInt(args, "width"), reqInt(args, "height"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "model_face_create_textures",
                "Batch version of model_face_create_texture: allocate a new material + GPU texture for "
                        + "MANY faces in one call. Strongly preferred over many single calls when texturing "
                        + "lots of faces — it validates the whole batch atomically (rejected if any face is "
                        + "invalid or already has a non-default material), regenerates the mesh UVs only once "
                        + "(avoiding the per-face duplication artifacts seen at mass scale), and records a "
                        + "single undo step. 'faces' is an array of objects, each "
                        + "{face_id, width, height, color:[r,g,b,a]} with width/height in [1,1024].",
                createTexturesSchema(),
                args -> editor.createFaceTextures(parseCreateSpecs(args.get("faces")))));

        // ---------- Resize ----------

        registry.register(new McpTool(
                "model_face_resize_texture",
                "Resize a face's GPU texture (nearest-neighbor); other faces sharing the material are "
                        + "also affected. Drops any open session for this face. Width/height in [1,1024]; "
                        + "sets autoResize=false so the editor won't rescale it on later geometry changes.",
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
        return schema().rgba("color", "RGBA [r,g,b,a], each 0..255");
    }

    private SchemaBuilder rectSchema() {
        return rgbaSchema()
                .intg("x", "Origin X")
                .intg("y", "Origin Y")
                .intg("w", "Width in pixels")
                .intg("h", "Height in pixels");
    }

    /** Schema for {@code model_face_create_textures}: a 'faces' array of spec objects. */
    private JsonNode createTexturesSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode facesArr = mapper.createObjectNode();
        facesArr.put("type", "array");
        facesArr.put("description",
                "Faces to create textures for; each an object {face_id, width, height, color:[r,g,b,a]}");

        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        ObjectNode itemProps = mapper.createObjectNode();
        itemProps.set("face_id", intNode("Face identifier"));
        itemProps.set("width", intNode("Initial texture width in pixels [1,1024]"));
        itemProps.set("height", intNode("Initial texture height in pixels [1,1024]"));
        ObjectNode color = mapper.createObjectNode();
        color.put("type", "array");
        color.put("description", "RGBA [r,g,b,a], each 0..255");
        color.put("minItems", 4);
        color.put("maxItems", 4);
        ObjectNode colorItems = mapper.createObjectNode();
        colorItems.put("type", "integer");
        color.set("items", colorItems);
        itemProps.set("color", color);
        item.set("properties", itemProps);
        ArrayNode itemRequired = mapper.createArrayNode();
        itemRequired.add("face_id");
        itemRequired.add("width");
        itemRequired.add("height");
        itemRequired.add("color");
        item.set("required", itemRequired);
        facesArr.set("items", item);

        properties.set("faces", facesArr);
        root.set("properties", properties);
        ArrayNode required = mapper.createArrayNode();
        required.add("faces");
        root.set("required", required);
        return root;
    }

    private ObjectNode intNode(String description) {
        ObjectNode def = mapper.createObjectNode();
        def.put("type", "integer");
        def.put("description", description);
        return def;
    }

    private JsonNode pixelsArraySchema() {
        return schema()
                .intg("face_id", "Face identifier")
                .intArr("pixels", "Flat [x,y,r,g,b,a, ...] array, 6 ints per pixel")
                .required("face_id", "pixels")
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

        SchemaBuilder intg(String name, String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", "integer");
            def.put("description", description);
            properties.set(name, def);
            return this;
        }

        /** Fixed-length [r,g,b,a] integer array. */
        SchemaBuilder rgba(String name, String description) {
            ObjectNode def = intArrNode(description);
            def.put("minItems", 4);
            def.put("maxItems", 4);
            properties.set(name, def);
            return this;
        }

        /** Variable-length integer array. */
        SchemaBuilder intArr(String name, String description) {
            properties.set(name, intArrNode(description));
            return this;
        }

        private ObjectNode intArrNode(String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", "array");
            def.put("description", description);
            ObjectNode items = mapper.createObjectNode();
            items.put("type", "integer");
            def.set("items", items);
            return def;
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

    /** Parse a flat [x,y,r,g,b,a, ...] array (6 ints per pixel). */
    private static List<FaceTextureEditingService.PixelEntry> parsePixels(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() % 6 != 0) {
            throw new IllegalArgumentException(
                    "pixels must be a flat int array [x,y,r,g,b,a, ...] with length divisible by 6");
        }
        List<FaceTextureEditingService.PixelEntry> out = new ArrayList<>(arr.size() / 6);
        for (int i = 0; i < arr.size(); i += 6) {
            out.add(new FaceTextureEditingService.PixelEntry(
                    intAt(arr, i), intAt(arr, i + 1),
                    intAt(arr, i + 2), intAt(arr, i + 3),
                    intAt(arr, i + 4), intAt(arr, i + 5)));
        }
        return out;
    }

    private static int intAt(JsonNode arr, int index) {
        JsonNode n = arr.get(index);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("pixels[" + index + "] is not a number");
        }
        return n.intValue();
    }

    /** Parse the 'faces' array for model_face_create_textures. */
    private static List<FaceTextureEditingService.CreateSpec> parseCreateSpecs(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("'faces' must be a non-empty array of face spec objects");
        }
        List<FaceTextureEditingService.CreateSpec> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode node = arr.get(i);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("faces[" + i + "] must be an object");
            }
            int faceId = reqInt(node, "face_id");
            int width = reqInt(node, "width");
            int height = reqInt(node, "height");
            int[] c = reqRgba(node);
            out.add(new FaceTextureEditingService.CreateSpec(
                    faceId, width, height, c[0], c[1], c[2], c[3]));
        }
        return out;
    }

    /** Parse the required [r,g,b,a] 'color' array argument. */
    private static int[] reqRgba(JsonNode args) {
        JsonNode n = args.get("color");
        if (n == null || !n.isArray() || n.size() != 4) {
            throw new IllegalArgumentException("Missing required [r,g,b,a] argument: color");
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            JsonNode c = n.get(i);
            if (c == null || !c.isNumber()) {
                throw new IllegalArgumentException("color[" + i + "] is not a number");
            }
            out[i] = c.intValue();
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
