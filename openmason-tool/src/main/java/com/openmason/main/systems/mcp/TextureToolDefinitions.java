package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.intAt;
import static com.openmason.main.systems.mcp.McpArgs.reqInt;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * Wires the {@link TextureEditingService} surface as MCP tools for the
 * Open Mason texture editor canvas: reads, canvas capture, tiny one-shot
 * mutations (fill, set_pixels), resize and export. Multi-step painting and
 * layer management live on the scripting surface ({@code om.canvas} via
 * run_python_script / {@code canvas_*} ops).
 *
 * <p>Names are snake_case and prefixed with {@code tex_} so they don't
 * collide with the model-editing toolset.
 */
public final class TextureToolDefinitions {

    private final TextureEditingService editor;
    private final CanvasCaptureService capture;
    private final ObjectMapper mapper;

    public TextureToolDefinitions(TextureEditingService editor, CanvasCaptureService capture,
                                  ObjectMapper mapper) {
        this.editor = editor;
        this.capture = capture;
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

        registry.register(new McpTool(
                "canvas_capture",
                "Capture the texture editor canvas as a PNG image — the visible layer composite, "
                        + "or one layer via 'layer'. Nearest-neighbor upscaled by a whole factor "
                        + "toward max_size (default 1024, range 64-2048) so pixel art stays crisp "
                        + "and viewable. Requires the texture editor to be open.",
                schema()
                        .intg("max_size", "Longest-side pixel target, 64-2048 (default 1024)")
                        .intg("layer", "Optional layer index; omit for the visible composite")
                        .build(),
                args -> capture.capture(
                        McpArgs.optInt(args, "max_size", CanvasCaptureService.DEFAULT_MAX_SIZE),
                        args.hasNonNull("layer") ? reqInt(args, "layer") : null)));

        // ---------- Mutate: pixel-level drawing ----------

        registry.register(new McpTool(
                "tex_set_pixels",
                "Per-pixel write (single or bulk) to the active layer. 'pixels' is a flat int array "
                        + "[x,y,r,g,b,a, x,y,r,g,b,a, ...] (6 values per pixel), one undo step. For "
                        + "multi-step painting (shapes, flood fill, noise, layers) use om.canvas via "
                        + "run_python_script.",
                pixelsArraySchema(),
                args -> editor.setPixels(parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "tex_fill",
                "Fill with a solid RGBA color: the whole active layer, or just 'rect' [x,y,w,h] "
                        + "when given. color [0,0,0,0] clears to transparent. For shapes/flood "
                        + "fill/noise/layers use om.canvas via run_python_script.",
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
