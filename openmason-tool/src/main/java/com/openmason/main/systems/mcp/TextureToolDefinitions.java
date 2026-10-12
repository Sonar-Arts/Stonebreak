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
    private final com.openmason.main.systems.io.AssetWriteService writes;
    private final ObjectMapper mapper;

    public TextureToolDefinitions(TextureEditingService editor, CanvasCaptureService capture,
                                  com.openmason.main.systems.io.AssetWriteService writes,
                                  ObjectMapper mapper) {
        this.editor = editor;
        this.capture = capture;
        this.writes = writes;
        this.mapper = mapper;
    }

    /**
     * Tools that act on one texture document: they take an optional {@code document} id
     * (see tex_editor_status) and default to the active document.
     */
    private static final java.util.Set<String> DOCUMENT_TOOLS = java.util.Set.of(
            "tex_get_canvas_info", "tex_get_region", "tex_list_layers", "canvas_capture",
            "tex_set_pixels", "tex_fill", "tex_close_document", "tex_save_project",
            "tex_layer_add", "tex_layer_remove", "tex_layer_duplicate", "tex_layer_move",
            "tex_layer_set", "tex_layer_merge_down", "tex_line", "tex_rect", "tex_ellipse",
            "tex_flood", "tex_noise", "tex_outline", "tex_paint_grid", "tex_set_selection",
            "tex_describe", "tex_export_png");

    public void registerAll(McpToolRegistry registry) {
        registerTools(tool -> registry.register(DOCUMENT_TOOLS.contains(tool.name()) ? documentScoped(tool) : tool));
    }

    /** Adds the optional {@code document} argument and runs the handler against that document. */
    private McpTool documentScoped(McpTool tool) {
        com.fasterxml.jackson.databind.node.ObjectNode schema = tool.inputSchema().deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode props = schema.has("properties")
                ? (com.fasterxml.jackson.databind.node.ObjectNode) schema.get("properties")
                : schema.putObject("properties");
        props.putObject("document").put("type", "string")
                .put("description", "Document id; default: active tab");
        McpTool.Handler handler = tool.handler();
        return new McpTool(tool.name(), tool.description(), schema,
                args -> editor.inDocument(McpArgs.optString(args, "document"), () -> {
                    try {
                        return handler.call(args);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                }));
    }

    private void registerTools(java.util.function.Consumer<McpTool> registry) {
        // ---------- Read ----------

        registry.accept(new McpTool(
                "tex_get_canvas_info",
                "Get the texture editor canvas info: dimensions, layer count, active layer, undo/redo availability.",
                schema().build(),
                args -> editor.getCanvasInfo()));

        registry.accept(new McpTool(
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

        registry.accept(new McpTool(
                "tex_list_layers",
                "List all layers in the texture project (index, name, visibility, opacity, active flag).",
                schema().build(),
                args -> editor.listLayers()));

        registry.accept(new McpTool(
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
                        args.hasNonNull("layer") ? reqInt(args, "layer") : null,
                        McpArgs.optString(args, "document"))));

        // ---------- Mutate: pixel-level drawing ----------

        registry.accept(new McpTool(
                "tex_set_pixels",
                "Per-pixel write (single or bulk) to the active layer. 'pixels' is a flat int array "
                        + "[x,y,r,g,b,a, x,y,r,g,b,a, ...] (6 values per pixel), one undo step. For "
                        + "multi-step painting (shapes, flood fill, noise, layers) use om.canvas via "
                        + "run_python_script.",
                pixelsArraySchema(),
                args -> editor.setPixels(parsePixels(args.get("pixels")))));

        registry.accept(new McpTool(
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

        registry.accept(new McpTool(
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

        // ---------- Editor window / session ----------

        registry.accept(new McpTool(
                "tex_editor_status",
                "Texture editor status: open?, document tabs (id, title, face|texture, path, dirty, "
                        + "size, face id) and the active one; for it: face session (material, UV region "
                        + "px), canvas, tool, symmetry, selection.",
                schema().build(),
                args -> editor.getEditorStatus()));

        registry.accept(new McpTool(
                "tex_open_editor",
                "Open a texture as a document tab (opens the editor if closed; never closes it). "
                        + "face_id: that model face like the 'Edit Texture' button (material created, "
                        + "pixels loaded, masked to the face, live preview on the model); an open face "
                        + "tab is just focused. width+height: new untitled tab. Neither: show the editor "
                        + "(new 16x16 tab if none). The tab becomes active; tex_* tools target the active "
                        + "tab unless given 'document'.",
                schema()
                        .intg("face_id", "Model face to edit (see model_face_list_textures)")
                        .intg("width", "Standalone canvas width (with height)")
                        .intg("height", "Standalone canvas height (with width)")
                        .build(),
                args -> editor.openEditor(
                        args.hasNonNull("face_id") ? reqInt(args, "face_id") : null,
                        args.hasNonNull("width") ? reqInt(args, "width") : null,
                        args.hasNonNull("height") ? reqInt(args, "height") : null)));

        registry.accept(new McpTool(
                "tex_close_document",
                "Close a document tab (default: active); the editor itself stays open. A face tab "
                        + "flushes to the model and saves it. An unsaved texture needs save=true (saved "
                        + "like tex_save_project, closed only if that succeeds) or discard=true.",
                schema().str("file_path", "With save: target .omt path")
                        .bool("save", "Save unsaved changes first, then close")
                        .bool("discard", "Close even with unsaved changes, dropping them").build(),
                this::closeDocument));

        registry.accept(new McpTool(
                "tex_flush",
                "Push pending canvas edits to the face's GPU texture now (keeps the editor open). "
                        + "Use before viewport_capture to see edits on the model.",
                schema().build(),
                args -> editor.flushToModel()));

        registry.accept(new McpTool(
                "tex_load_project",
                "Open a .omt texture project (layers + palette) as a document tab and show the "
                        + "editor; a tab already showing that file is focused instead (its edits kept).",
                schema().str("file_path", "Absolute .omt path").required("file_path").build(),
                args -> editor.loadProject(reqString(args, "file_path"))));

        registry.accept(new McpTool(
                "tex_save_project",
                "Save a document tab as a .omt. Without file_path: re-save its .omt, or ask in the "
                        + "Save Sheet. Paths must be in a writable root (save_targets).",
                savePathSchema("Target .omt path: absolute, project:<rel>, or a bare name").build(),
                args -> {
                    // The writer runs later on the main thread: pin the document now.
                    String doc = editor.resolveDocumentId();
                    return saveOrInPlace(com.openmason.main.systems.io.WriteKind.OMT, args,
                            editor.currentProjectPath(), "texture",
                            p -> editor.inDocument(doc, () -> editor.saveProject(p.toString())));
                }));

        // ---------- Layers ----------

        registry.accept(new McpTool(
                "tex_layer_add",
                "Add an empty layer on top (becomes active). One undo step. Returns the layer list.",
                schema().str("name", "Layer name (default 'Layer N')").build(),
                args -> editor.addLayer(McpArgs.optString(args, "name"))));

        registry.accept(new McpTool(
                "tex_layer_remove",
                "Remove the layer at index (refuses to remove the last layer). One undo step.",
                schema().intg("index", "Layer index").required("index").build(),
                args -> editor.removeLayer(reqInt(args, "index"))));

        registry.accept(new McpTool(
                "tex_layer_duplicate",
                "Duplicate the layer at index (copy inserted above it). One undo step.",
                schema().intg("index", "Layer index").required("index").build(),
                args -> editor.duplicateLayer(reqInt(args, "index"))));

        registry.accept(new McpTool(
                "tex_layer_move",
                "Reorder: move the layer at 'from' to position 'to' (0 = bottom). One undo step.",
                schema().intg("from", "Current index").intg("to", "Target index")
                        .required("from", "to").build(),
                args -> editor.moveLayer(reqInt(args, "from"), reqInt(args, "to"))));

        registry.accept(new McpTool(
                "tex_layer_set",
                "Update a layer's name / visibility / opacity (0..1) and/or make it the active "
                        + "paint target. Layers composite bottom→top with straight alpha-over "
                        + "(no blend modes). One undo step.",
                schema()
                        .intg("index", "Layer index")
                        .bool("active", "Make this the active layer")
                        .bool("visible", "Show/hide")
                        .str("name", "Rename")
                        .num("opacity", "0..1")
                        .required("index")
                        .build(),
                args -> editor.setLayer(reqInt(args, "index"),
                        args.hasNonNull("active") ? McpArgs.reqBool(args, "active") : null,
                        args.hasNonNull("visible") ? McpArgs.reqBool(args, "visible") : null,
                        McpArgs.optString(args, "name"),
                        McpArgs.optFloatBoxed(args, "opacity"))));

        registry.accept(new McpTool(
                "tex_layer_merge_down",
                "Merge layer 'index' onto the layer below it (alpha-over at its opacity) and "
                        + "remove it. One undo step.",
                schema().intg("index", "Layer index (>= 1)").required("index").build(),
                args -> editor.mergeLayerDown(reqInt(args, "index"))));

        // ---------- Mutate: shapes / filters on the active layer ----------

        registry.accept(new McpTool(
                "tex_line",
                "1-px Bresenham line from (x0,y0) to (x1,y1) on the active layer.",
                rgbaSchema()
                        .intg("x0", "Start X").intg("y0", "Start Y")
                        .intg("x1", "End X").intg("y1", "End Y")
                        .required("x0", "y0", "x1", "y1", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.line(reqInt(args, "x0"), reqInt(args, "y0"),
                            reqInt(args, "x1"), reqInt(args, "y1"), c[0], c[1], c[2], c[3]);
                }));

        registry.accept(new McpTool(
                "tex_rect",
                "Rectangle [x,y,w,h] on the active layer — filled, or a 1-px outline (filled=false).",
                rgbaSchema()
                        .intArr("rect", "[x,y,w,h]")
                        .bool("filled", "Fill (default true) or outline only")
                        .required("rect", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    int[] r = McpArgs.reqIntArray(args, "rect");
                    if (r.length != 4) throw new IllegalArgumentException("rect must be [x,y,w,h]");
                    boolean filled = McpArgs.optBool(args, "filled", true);
                    return filled
                            ? editor.fillRect(r[0], r[1], r[2], r[3], c[0], c[1], c[2], c[3])
                            : editor.rectOutline(r[0], r[1], r[2], r[3], c[0], c[1], c[2], c[3]);
                }));

        registry.accept(new McpTool(
                "tex_ellipse",
                "Ellipse inscribed in [x,y,w,h] on the active layer — filled, or a 1-px ring.",
                rgbaSchema()
                        .intArr("rect", "Bounding box [x,y,w,h]")
                        .bool("filled", "Fill (default true) or ring only")
                        .required("rect", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    int[] r = McpArgs.reqIntArray(args, "rect");
                    if (r.length != 4) throw new IllegalArgumentException("rect must be [x,y,w,h]");
                    return editor.ellipse(r[0], r[1], r[2], r[3], c[0], c[1], c[2], c[3],
                            McpArgs.optBool(args, "filled", true));
                }));

        registry.accept(new McpTool(
                "tex_flood",
                "4-connected flood fill from (x,y) on the active layer; the face mask and "
                        + "selection bound the fill exactly like the bucket tool.",
                rgbaSchema()
                        .intg("x", "Seed X").intg("y", "Seed Y")
                        .required("x", "y", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    return editor.flood(reqInt(args, "x"), reqInt(args, "y"), c[0], c[1], c[2], c[3]);
                }));

        registry.accept(new McpTool(
                "tex_noise",
                "Apply the editor's procedural noise filter to the active layer (honours the "
                        + "selection). Noise perturbs existing RGB and keeps alpha — fill first; "
                        + "noise on transparent pixels does nothing. strength 0..1, scale >= 0.1 "
                        + "(bigger = larger blobs), gradient=true for smooth ramps, blur/spread/"
                        + "edge_softness 0..1 diffusion, octaves 1..8.",
                schema()
                        .enumStr("generator", "Noise type (default simplex)", "simplex", "value", "white")
                        .intg("seed", "Seed (default 0)")
                        .num("strength", "0..1 (default 0.3)")
                        .num("scale", ">= 0.1 (default 4)")
                        .bool("gradient", "Smooth gradient instead of per-pixel (default false)")
                        .num("blur", "0..1 (default 0)")
                        .intg("octaves", "1..8 (default 1)")
                        .num("spread", "0..1 (default 0.5)")
                        .num("edge_softness", "0..1 (default 0)")
                        .build(),
                args -> editor.noise(
                        McpArgs.optString(args, "generator"),
                        McpArgs.optInt(args, "seed", 0),
                        (float) McpArgs.optDouble(args, "strength", 0.3),
                        (float) McpArgs.optDouble(args, "scale", 4.0),
                        McpArgs.optBool(args, "gradient", false),
                        (float) McpArgs.optDouble(args, "blur", 0.0),
                        McpArgs.optInt(args, "octaves", 1),
                        (float) McpArgs.optDouble(args, "spread", 0.5),
                        (float) McpArgs.optDouble(args, "edge_softness", 0.0))));

        registry.accept(new McpTool(
                "tex_outline",
                "Auto-outline the active layer's opaque silhouette. inside=false (default) grows "
                        + "a 1-px border into transparent neighbours; inside=true recolours the "
                        + "silhouette's own edge row. Without 'color' each outline pixel is a "
                        + "darker, cooler shade of its neighbour (never flat black) — the standard "
                        + "pixel-art outline rule.",
                schema()
                        .bool("inside", "Inner outline instead of outer (default false)")
                        .rgba("color", "Optional fixed RGBA outline colour")
                        .build(),
                args -> editor.outline(McpArgs.optBool(args, "inside", false),
                        args.hasNonNull("color") ? reqRgba(args) : null)));

        registry.accept(new McpTool(
                "tex_paint_grid",
                "Paint pixel art from text: 'rows' is a list of equal-length glyph strings, "
                        + "'legend' maps each glyph to a colour ('#rrggbb', '#rrggbbaa' or 'r,g,b[,a]'). "
                        + "'.' leaves a pixel untouched (or clears it when clear_dots=true), ' ' always "
                        + "skips. Placed with its top-left at (x,y). Inverse of tex_describe — "
                        + "describe, edit the text, paint it back. One undo step.",
                schema()
                        .strArray("rows", "Glyph rows, top to bottom")
                        .strMap("legend", "glyph → colour")
                        .intg("x", "Left edge (default 0)").intg("y", "Top edge (default 0)")
                        .bool("clear_dots", "'.' writes transparent instead of skipping (default false)")
                        .required("rows", "legend")
                        .build(),
                args -> editor.paintGrid(McpArgs.optStringList(args, "rows"),
                        McpArgs.optStringMap(args, "legend"),
                        McpArgs.optInt(args, "x", 0), McpArgs.optInt(args, "y", 0),
                        McpArgs.optBool(args, "clear_dots", false))));

        // ---------- Selection / symmetry / interactive tool ----------

        registry.accept(new McpTool(
                "tex_set_selection",
                "Set a rectangular selection [x,y,w,h] that constrains every later paint op "
                        + "(fill, noise, flood, grid...), or clear it by omitting rect.",
                schema().intArr("rect", "[x,y,w,h]; omit to clear").build(),
                args -> editor.setSelection(McpArgs.optIntArray(args, "rect"))));

        registry.accept(new McpTool(
                "tex_set_symmetry",
                "Configure the editor's mirror-painting symmetry for the user's interactive "
                        + "brush strokes: mode none|horizontal|vertical|quadrant, axis offsets in px "
                        + "from centre, and axis-line display.",
                schema()
                        .enumStr("mode", "Symmetry mode", "none", "horizontal", "vertical", "quadrant")
                        .intg("offset_x", "Vertical axis offset from centre (px)")
                        .intg("offset_y", "Horizontal axis offset from centre (px)")
                        .bool("show_axes", "Draw axis guide lines")
                        .build(),
                args -> editor.setSymmetry(McpArgs.optString(args, "mode"),
                        args.hasNonNull("offset_x") ? reqInt(args, "offset_x") : null,
                        args.hasNonNull("offset_y") ? reqInt(args, "offset_y") : null,
                        args.hasNonNull("show_axes") ? McpArgs.reqBool(args, "show_axes") : null)));

        registry.accept(new McpTool(
                "tex_set_tool",
                "Select the toolbar tool the USER will paint with (Pencil, Eraser, Fill, Line, "
                        + "Shapes, Color Picker, selection tools...) and/or the current paint colour. "
                        + "Omit both to just read the tool state. Useful for handing the user a "
                        + "prepared setup; MCP drawing itself uses tex_* ops directly.",
                schema()
                        .str("tool", "Tool name (case-insensitive); see availableTools in the result")
                        .rgba("color", "Current paint colour RGBA")
                        .build(),
                args -> {
                    String tool = McpArgs.optString(args, "tool");
                    int[] c = args.hasNonNull("color") ? reqRgba(args) : null;
                    return tool == null && c == null ? editor.getToolStatus() : editor.setTool(tool, c);
                }));

        // ---------- Describe (vision-free read) ----------

        registry.accept(new McpTool(
                "tex_describe",
                "Read pixel art as TEXT for models without vision: a glyph grid with row/column "
                        + "rulers (one glyph per colour, '.' = transparent), a legend (glyph → "
                        + "hex, rough colour name, pixel count/%), the opaque bounding box, "
                        + "left-right / top-bottom mirror-symmetry scores, orphan pixels (opaque with "
                        + "no 4-neighbour — reads as dirt), and optional per-row run-lengths "
                        + "(glyph@x0-x1) for exact coordinates, and hex=true for exact per-pixel "
                        + "hex rows (shading/ramp inspection). tolerance merges near shades "
                        + "(e.g. 24 collapses noise speckle) so the structure stays legible; "
                        + "max_colors caps glyphs (rest merge to nearest). Default: active layer; "
                        + "layer=-1 for the visible composite. Far cheaper than tex_get_region.",
                describeSchema()
                        .intg("layer", "Layer index; -1 = visible composite; omit = active layer")
                        .build(),
                args -> editor.describe(
                        args.hasNonNull("layer") ? reqInt(args, "layer") : null,
                        McpArgs.optIntArray(args, "rect"), describeOptions(args))));

        // ---------- Export ----------

        registry.accept(new McpTool(
                "tex_export_png",
                "Flatten visible layers and export a PNG. file_path: absolute, exports:<rel>, "
                        + "project:<rel> or a bare name (lands in the exports root); omit to ask "
                        + "the user in the Save Sheet.",
                savePathSchema("Target .png path").build(),
                args -> writes.save(
                        com.openmason.main.systems.io.AssetWriteService.WriteRequest.of(
                                com.openmason.main.systems.io.WriteKind.PNG,
                                McpArgs.optString(args, "file_path"),
                                McpArgs.optBool(args, "prompt", false),
                                McpArgs.optBool(args, "overwrite", false), "texture"),
                        exportWriter())));
    }

    /**
     * tex_close_document: optionally save the tab first (same routing as tex_save_project), and
     * close it only when nothing unsaved would be lost.
     */
    private Object closeDocument(JsonNode args) {
        boolean save = McpArgs.optBool(args, "save", false);
        boolean discard = McpArgs.optBool(args, "discard", false);
        if (save && discard) {
            throw new IllegalArgumentException("save and discard are mutually exclusive");
        }
        if (!save) {
            return editor.closeDocument(discard);
        }
        String doc = editor.resolveDocumentId();
        TextureEditingService.ProjectSnapshot snap = editor.projectSnapshot();
        Object saved = null;
        if (!snap.faceRegionActive() && snap.dirty()) {
            saved = saveOrInPlace(com.openmason.main.systems.io.WriteKind.OMT, args, snap.path(), "texture",
                    p -> editor.inDocument(doc, () -> editor.saveProject(p.toString())));
            if (saved instanceof com.openmason.main.systems.io.AssetWriteService.WriteOutcome o && !o.ok()) {
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("closed", false);
                out.put("save", o);
                return out; // declined / failed: the tab stays open with its changes
            }
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("closed", true);
        if (saved != null) {
            out.put("save", saved);
        }
        out.put("editor", editor.closeDocument(false));
        return out;
    }

    /** PNG export writer pinned to the document targeted now (the writer runs later). */
    private com.openmason.main.systems.io.AssetWriteService.Writer exportWriter() {
        String doc = editor.resolveDocumentId();
        return p -> editor.inDocument(doc, () -> editor.exportPng(p.toString()));
    }

    private McpSchema savePathSchema(String pathDescription) {
        return schema()
                .str("file_path", pathDescription)
                .bool("prompt", "Always ask the user in the in-app Save Sheet")
                .bool("overwrite", "Acknowledge replacing an existing file");
    }

    /** Shared save routing: explicit path → sandbox+policy; none → in-place or Save Sheet. */
    private Object saveOrInPlace(com.openmason.main.systems.io.WriteKind kind, JsonNode args,
                                 String currentPath, String suggestedName,
                                 com.openmason.main.systems.io.AssetWriteService.Writer writer) {
        String path = McpArgs.optString(args, "file_path");
        boolean prompt = McpArgs.optBool(args, "prompt", false);
        if (path == null && !prompt && currentPath != null && !currentPath.isBlank()) {
            return writes.saveInPlace(kind, currentPath, writer);
        }
        return writes.save(com.openmason.main.systems.io.AssetWriteService.WriteRequest.of(
                kind, path, prompt, McpArgs.optBool(args, "overwrite", false), suggestedName), writer);
    }

    // ===================== Schema helpers =====================

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    /** Shared schema for the describe tools (rect + codec options). */
    static McpSchema describeSchema(McpSchema s) {
        return s.intArr("rect", "Sub-rectangle [x,y,w,h]; omit for the whole image")
                .intg("tolerance", "Merge colours within this RGB distance into one glyph (default 0)")
                .intg("max_colors", "Max glyphs, 1-" + PixelTextCodec.MAX_COLORS + " (default all)")
                .intg("alpha_threshold", "Alpha below this counts as transparent (default 8)")
                .bool("rle", "Also emit per-row run-lengths (default false)")
                .bool("hex", "Also emit hexRows: exact per-pixel hex (rrggbb[aa], '......' = transparent), "
                        + "unaffected by tolerance — for judging shading/ramps (default false)")
                .bool("rulers", "Row/column coordinate rulers on the grid (default true)");
    }

    private McpSchema describeSchema() {
        return describeSchema(schema());
    }

    static PixelTextCodec.Options describeOptions(JsonNode args) {
        PixelTextCodec.Options d = PixelTextCodec.Options.defaults();
        return new PixelTextCodec.Options(
                McpArgs.optInt(args, "tolerance", d.tolerance()),
                McpArgs.optInt(args, "max_colors", d.maxColors()),
                McpArgs.optInt(args, "alpha_threshold", d.alphaThreshold()),
                McpArgs.optBool(args, "rle", d.rle()),
                McpArgs.optBool(args, "rulers", d.rulers()),
                McpArgs.optBool(args, "hex", d.hexGrid()));
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
