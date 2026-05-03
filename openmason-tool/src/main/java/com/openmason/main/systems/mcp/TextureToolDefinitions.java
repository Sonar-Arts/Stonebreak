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
                "tex_list_layers",
                "List all layers in the texture project (index, name, visibility, opacity, active flag).",
                schema().build(),
                args -> editor.listLayers()));

        // ---------- Mutate: pixel-level drawing ----------

        registry.register(new McpTool(
                "tex_set_pixel",
                "Set a single pixel on the active layer. Channel values 0-255. Recorded as one undoable step.",
                rgbaSchema()
                        .intg("x", "Pixel X").intg("y", "Pixel Y")
                        .required("x", "y", "r", "g", "b", "a")
                        .build(),
                args -> editor.setPixel(
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "tex_set_pixels",
                "Bulk per-pixel write. 'pixels' is an array of {x,y,r,g,b,a} entries. All recorded as a single undo step.",
                pixelsArraySchema(),
                args -> editor.setPixels(parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "tex_fill_canvas",
                "Fill every editable pixel of the active layer with a solid RGBA color.",
                rgbaSchema().required("r", "g", "b", "a").build(),
                args -> editor.fillCanvas(
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "tex_clear_canvas",
                "Clear the active layer to transparent (RGBA 0,0,0,0).",
                schema().build(),
                args -> editor.clearCanvas()));

        registry.register(new McpTool(
                "tex_fill_rect",
                "Fill a solid rectangle (origin x,y with width w and height h) with an RGBA color.",
                rectSchema().required("x", "y", "w", "h", "r", "g", "b", "a").build(),
                args -> editor.fillRect(
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "tex_draw_rect",
                "Draw a rectangle outline (1 pixel thick) at origin (x,y) with size w x h.",
                rectSchema().required("x", "y", "w", "h", "r", "g", "b", "a").build(),
                args -> editor.drawRect(
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "tex_draw_line",
                "Draw a 1-pixel Bresenham line between (x0,y0) and (x1,y1).",
                rgbaSchema()
                        .intg("x0", "Start X").intg("y0", "Start Y")
                        .intg("x1", "End X").intg("y1", "End Y")
                        .required("x0", "y0", "x1", "y1", "r", "g", "b", "a")
                        .build(),
                args -> editor.drawLine(
                        reqInt(args, "x0"), reqInt(args, "y0"),
                        reqInt(args, "x1"), reqInt(args, "y1"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

        registry.register(new McpTool(
                "tex_flood_fill",
                "Flood-fill (4-connected) starting at (x,y) with an RGBA color. Respects layer mask + active selection.",
                rgbaSchema()
                        .intg("x", "Seed X").intg("y", "Seed Y")
                        .required("x", "y", "r", "g", "b", "a")
                        .build(),
                args -> editor.floodFill(
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "r"), reqInt(args, "g"),
                        reqInt(args, "b"), reqInt(args, "a"))));

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

        SchemaBuilder str(String name, String description) { return prop(name, "string", description); }
        SchemaBuilder num(String name, String description) { return prop(name, "number", description); }
        SchemaBuilder intg(String name, String description) { return prop(name, "integer", description); }
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

    // ===================== Argument parsing =====================

    private static List<TextureEditingService.PixelEntry> parsePixels(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("Missing required array argument: pixels");
        }
        List<TextureEditingService.PixelEntry> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode p = arr.get(i);
            out.add(new TextureEditingService.PixelEntry(
                    reqInt(p, "x"), reqInt(p, "y"),
                    reqInt(p, "r"), reqInt(p, "g"),
                    reqInt(p, "b"), reqInt(p, "a")));
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
