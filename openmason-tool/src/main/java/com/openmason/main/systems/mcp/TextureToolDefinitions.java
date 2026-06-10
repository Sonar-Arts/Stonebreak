package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the {@link TextureEditingService} surface as MCP tools for the
 * Open Mason texture editor canvas (per-pixel edits, drawing primitives,
 * flood fill, noise, layers, undo/redo, export).
 *
 * <p>Names are snake_case and prefixed with {@code tex_} so they don't
 * collide with the model-editing toolset.
 */
public final class TextureToolDefinitions {

    private final TextureEditingService editor;
    private final ObjectMapper mapper;

    public TextureToolDefinitions(TextureEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "tex_get_canvas_info",
                "Get the texture editor canvas info: dimensions, layer count, active layer, undo/redo availability.",
                schema().build(),
                args -> editor.getCanvasInfo()));

        registry.register(new McpTool(
                "tex_get_pixel",
                "Read the RGBA color of a single pixel on the active layer.",
                schema().intg("x", "Pixel X").intg("y", "Pixel Y").required("x", "y").build(),
                args -> editor.getPixel(reqInt(args, "x"), reqInt(args, "y"))));

        registry.register(new McpTool(
                "tex_get_region",
                "Read a rectangular region of the active layer as a flat [r,g,b,a, ...] array, "
                        + "row-major from (x,y). Far cheaper than per-pixel tex_get_pixel calls.",
                schema()
                        .intg("x", "Origin X").intg("y", "Origin Y")
                        .intg("w", "Width in pixels").intg("h", "Height in pixels")
                        .required("x", "y", "w", "h")
                        .build(),
                args -> editor.getRegion(
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"))));

        registry.register(new McpTool(
                "tex_list_layers",
                "List all layers in the texture project (index, name, visibility, opacity, active flag).",
                schema().build(),
                args -> editor.listLayers()));

        // ---------- Mutate: pixel-level drawing ----------

        registry.register(new McpTool(
                "tex_set_pixel",
                "Set a single pixel on the active layer. Recorded as one undoable step.",
                rgbaSchema()
                        .intg("x", "Pixel X").intg("y", "Pixel Y")
                        .required("x", "y", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.setPixel(
                            reqInt(args, "x"), reqInt(args, "y"), c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "tex_set_pixels",
                "Bulk per-pixel write. 'pixels' is a flat int array [x,y,r,g,b,a, x,y,r,g,b,a, ...] "
                        + "(6 values per pixel). All recorded as a single undo step.",
                pixelsArraySchema(),
                args -> editor.setPixels(parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "tex_fill_canvas",
                "Fill every editable pixel of the active layer with a solid RGBA color.",
                rgbaSchema().required("color").build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.fillCanvas(c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "tex_clear_canvas",
                "Clear the active layer to transparent (RGBA 0,0,0,0).",
                schema().build(),
                args -> editor.clearCanvas()));

        registry.register(new McpTool(
                "tex_fill_rect",
                "Fill a solid rectangle (origin x,y with width w and height h) with an RGBA color.",
                rectSchema().required("x", "y", "w", "h", "color").build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.fillRect(
                            reqInt(args, "x"), reqInt(args, "y"),
                            reqInt(args, "w"), reqInt(args, "h"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "tex_draw_rect",
                "Draw a rectangle outline (1 pixel thick) at origin (x,y) with size w x h.",
                rectSchema().required("x", "y", "w", "h", "color").build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.drawRect(
                            reqInt(args, "x"), reqInt(args, "y"),
                            reqInt(args, "w"), reqInt(args, "h"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "tex_draw_line",
                "Draw a 1-pixel Bresenham line between (x0,y0) and (x1,y1).",
                rgbaSchema()
                        .intg("x0", "Start X").intg("y0", "Start Y")
                        .intg("x1", "End X").intg("y1", "End Y")
                        .required("x0", "y0", "x1", "y1", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.drawLine(
                            reqInt(args, "x0"), reqInt(args, "y0"),
                            reqInt(args, "x1"), reqInt(args, "y1"),
                            c[0], c[1], c[2], c[3]);
                }));

        registry.register(new McpTool(
                "tex_flood_fill",
                "Flood-fill (4-connected) starting at (x,y) with an RGBA color. Respects layer mask + active selection.",
                rgbaSchema()
                        .intg("x", "Seed X").intg("y", "Seed Y")
                        .required("x", "y", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.floodFill(reqInt(args, "x"), reqInt(args, "y"),
                            c[0], c[1], c[2], c[3]);
                }));

        // ---------- Filters ----------

        registry.register(new McpTool(
                "tex_apply_noise",
                "Apply procedural noise to the active layer. generator=SIMPLEX|VALUE|WHITE; "
                        + "strength/spread/edge_softness/blur in [0,1]; scale > 0.1; octaves 1..8. "
                        + "Defaults: strength=0.5, scale=1.0, gradient=false, blur=0, octaves=1, spread=0.5, edge_softness=0.",
                schema()
                        .str("generator", "Noise generator: SIMPLEX, VALUE, or WHITE")
                        .num("seed", "Random seed (default 0)")
                        .num("strength", "Noise strength 0..1 (default 0.5)")
                        .num("scale", "Noise scale (default 1.0)")
                        .bool("gradient", "Apply diagonal gradient bias (default false)")
                        .num("blur", "Box blur 0..1 (default 0)")
                        .num("octaves", "FBM octaves 1..8 (default 1)")
                        .num("spread", "Contrast spread 0..1 (default 0.5)")
                        .num("edge_softness", "Smoothstep softness 0..1 (default 0)")
                        .required("generator")
                        .build(),
                args -> editor.applyNoise(
                        reqString(args, "generator"),
                        (long) optDouble(args, "seed", 0),
                        (float) optDouble(args, "strength", 0.5),
                        (float) optDouble(args, "scale", 1.0),
                        optBool(args, "gradient", false),
                        (float) optDouble(args, "blur", 0.0),
                        (int) optDouble(args, "octaves", 1),
                        (float) optDouble(args, "spread", 0.5),
                        (float) optDouble(args, "edge_softness", 0.0))));

        // ---------- Undo / redo ----------

        registry.register(new McpTool(
                "tex_undo",
                "Undo the most recent texture-editor command on the active project.",
                schema().build(),
                args -> editor.undo()));

        registry.register(new McpTool(
                "tex_redo",
                "Redo the most recently undone texture-editor command.",
                schema().build(),
                args -> editor.redo()));

        // ---------- Layers ----------

        registry.register(new McpTool(
                "tex_add_layer",
                "Add a new empty layer on top of the stack. The new layer becomes the active layer.",
                schema().str("name", "Layer name").required("name").build(),
                args -> editor.addLayer(reqString(args, "name"))));

        registry.register(new McpTool(
                "tex_remove_layer",
                "Remove the layer at the given index. Refuses to remove the last remaining layer.",
                schema().intg("index", "Layer index").required("index").build(),
                args -> editor.removeLayer(reqInt(args, "index"))));

        registry.register(new McpTool(
                "tex_set_active_layer",
                "Make the layer at the given index the active drawing target.",
                schema().intg("index", "Layer index").required("index").build(),
                args -> editor.setActiveLayer(reqInt(args, "index"))));

        registry.register(new McpTool(
                "tex_set_layer_visibility",
                "Show or hide the layer at the given index.",
                schema()
                        .intg("index", "Layer index")
                        .bool("visible", "true to show, false to hide")
                        .required("index", "visible")
                        .build(),
                args -> editor.setLayerVisibility(reqInt(args, "index"), args.get("visible").asBoolean())));

        registry.register(new McpTool(
                "tex_rename_layer",
                "Rename the layer at the given index.",
                schema()
                        .intg("index", "Layer index")
                        .str("name", "New layer name")
                        .required("index", "name")
                        .build(),
                args -> editor.renameLayer(reqInt(args, "index"), reqString(args, "name"))));

        // ---------- Resize ----------

        registry.register(new McpTool(
                "tex_resize_face_texture",
                "Resize the GPU texture of the face currently open in the texture editor. "
                        + "Nearest-neighbor rescale, UVs unchanged (normalized within material). "
                        + "Requires a face region to be active (open a face for editing first). "
                        + "Width/height clamped to [1, 1024].",
                schema()
                        .intg("width", "New texture width in pixels")
                        .intg("height", "New texture height in pixels")
                        .required("width", "height")
                        .build(),
                args -> editor.resizeFaceTexture(reqInt(args, "width"), reqInt(args, "height"))));

        // ---------- Export ----------

        registry.register(new McpTool(
                "tex_export_png",
                "Flatten visible layers and export to a PNG file at the given absolute path.",
                schema().str("file_path", "Absolute output PNG path").required("file_path").build(),
                args -> editor.exportPng(reqString(args, "file_path"))));
    }

    // ===================== Schema builder =====================

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

    private JsonNode pixelsArraySchema() {
        return schema()
                .intArr("pixels", "Flat [x,y,r,g,b,a, ...] array, 6 ints per pixel")
                .required("pixels")
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

        SchemaBuilder str(String name, String description) { return prop(name, "string", description); }
        SchemaBuilder num(String name, String description) { return prop(name, "number", description); }
        SchemaBuilder intg(String name, String description) { return prop(name, "integer", description); }
        SchemaBuilder bool(String name, String description) { return prop(name, "boolean", description); }

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

    // ===================== Argument parsing =====================

    /** Parse a flat [x,y,r,g,b,a, ...] array (6 ints per pixel). */
    private static List<TextureEditingService.PixelEntry> parsePixels(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() % 6 != 0) {
            throw new IllegalArgumentException(
                    "pixels must be a flat int array [x,y,r,g,b,a, ...] with length divisible by 6");
        }
        List<TextureEditingService.PixelEntry> out = new ArrayList<>(arr.size() / 6);
        for (int i = 0; i < arr.size(); i += 6) {
            out.add(new TextureEditingService.PixelEntry(
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

    private static String reqString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string argument: " + key);
        }
        return n.asText();
    }

    private static int reqInt(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing required integer argument: " + key);
        }
        return n.intValue();
    }

    private static double optDouble(JsonNode args, String key, double fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? fallback : n.doubleValue();
    }

    private static boolean optBool(JsonNode args, String key, boolean fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isBoolean()) ? fallback : n.asBoolean();
    }
}
