package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.intAt;
import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optDouble;
import static com.openmason.main.systems.mcp.McpArgs.reqInt;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

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
                "tex_get_region",
                "Read a rectangular region of the active layer as a flat [r,g,b,a, ...] array, "
                        + "row-major from (x,y). Use w=h=1 for a single pixel.",
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
                "tex_set_pixels",
                "Per-pixel write (single or bulk). 'pixels' is a flat int array "
                        + "[x,y,r,g,b,a, x,y,r,g,b,a, ...] (6 values per pixel), one undo step.",
                pixelsArraySchema(),
                args -> editor.setPixels(parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "tex_fill",
                "Fill with a solid RGBA color: the whole active layer, or just 'rect' [x,y,w,h] "
                        + "when given. color [0,0,0,0] clears to transparent.",
                rgbaSchema()
                        .intArr("rect", "Optional [x,y,w,h] rectangle; omit to fill the whole layer")
                        .required("color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    int[] rect = McpArgs.optIntArray(args, "rect");
                    if (rect != null) {
                        if (rect.length != 4) {
                            throw new IllegalArgumentException("rect must be [x,y,w,h]");
                        }
                        return editor.fillRect(rect[0], rect[1], rect[2], rect[3],
                                c[0], c[1], c[2], c[3]);
                    }
                    if (c[0] == 0 && c[1] == 0 && c[2] == 0 && c[3] == 0) {
                        return editor.clearCanvas();
                    }
                    return editor.fillCanvas(c[0], c[1], c[2], c[3]);
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
                "tex_set_layer",
                "Update the layer at 'index': any of name (rename), visible (show/hide), "
                        + "active=true (make it the drawing target). Omitted fields keep their value.",
                schema()
                        .intg("index", "Layer index")
                        .str("name", "New layer name")
                        .bool("visible", "true to show, false to hide")
                        .bool("active", "true to make this the active drawing layer")
                        .required("index")
                        .build(),
                args -> {
                    int index = reqInt(args, "index");
                    Object last = null;
                    String name = McpArgs.optString(args, "name");
                    if (name != null) last = editor.renameLayer(index, name);
                    JsonNode visible = args.get("visible");
                    if (visible != null && visible.isBoolean()) {
                        last = editor.setLayerVisibility(index, visible.asBoolean());
                    }
                    if (optBool(args, "active", false)) last = editor.setActiveLayer(index);
                    if (last == null) {
                        throw new IllegalArgumentException(
                                "pass at least one of name, visible, active");
                    }
                    return last;
                }));

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

    // ===================== Schema helpers =====================

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private McpSchema rgbaSchema() {
        return schema().rgba("color", "RGBA [r,g,b,a], each 0..255");
    }

    private McpSchema rectSchema() {
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
                    intAt(arr, i, "pixels"), intAt(arr, i + 1, "pixels"),
                    intAt(arr, i + 2, "pixels"), intAt(arr, i + 3, "pixels"),
                    intAt(arr, i + 4, "pixels"), intAt(arr, i + 5, "pixels")));
        }
        return out;
    }

    /** Parse the required [r,g,b,a] 'color' array argument. */
    private static int[] reqRgba(JsonNode args) {
        return McpArgs.reqRgba(args, "color");
    }
}
