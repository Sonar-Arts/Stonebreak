package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.intAt;
import static com.openmason.main.systems.mcp.McpArgs.reqInt;

/**
 * Registers MCP tools for direct per-face texture editing on the loaded model,
 * exposing {@link FaceTextureEditingService}.
 *
 * <p>Distinguished from the {@code tex_*} surface (which requires the texture
 * editor to be open) by the {@code model_face_*} prefix — these tools operate
 * directly on each face's GPU texture as sessionless one-shot edits. Multi-step
 * painting (shapes, flood fill, noise) lives on the scripting surface:
 * {@code om.tex} via run_python_script, or {@code texture_*} ops via
 * run_model_ops.
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
                "List model faces with texture metadata as compact rows {faceId, materialId, w, h, "
                        + "orientation, part}. detail=true adds the full record (material name, normal, "
                        + "UV region/rotation, suggested sizes, autoResize). Filter with part (name) "
                        + "and/or face_ids; paginated as {total, offset, faces} (default limit 200).",
                schema()
                        .bool("detail", "Include the full per-face record")
                        .str("part", "Only faces belonging to this part name")
                        .intArr("face_ids", "Only these face ids")
                        .intg("offset", "Pagination offset (default 0)")
                        .intg("limit", "Max rows returned (default 200)")
                        .build(),
                args -> listTextures(args)));

        // ---------- Pixel read ----------

        registry.register(new McpTool(
                "model_face_get_region",
                "Read a rectangular region of a face's texture as a flat [r,g,b,a, ...] array, "
                        + "row-major from (x,y). Use w=h=1 for a single pixel. Reads the GPU "
                        + "texture directly — no session needed.",
                schema()
                        .intg("face_id", "Face identifier")
                        .intg("x", "Origin X").intg("y", "Origin Y")
                        .intg("w", "Width in pixels").intg("h", "Height in pixels")
                        .required("face_id", "x", "y", "w", "h")
                        .build(),
                args -> editor.getRegion(reqInt(args, "face_id"),
                        reqInt(args, "x"), reqInt(args, "y"),
                        reqInt(args, "w"), reqInt(args, "h"))));

        // ---------- One-shot mutations (sessionless) ----------

        registry.register(new McpTool(
                "model_face_set_pixels",
                "Per-pixel write (single or bulk) applied directly to a face's texture — one call, "
                        + "one undo step. 'pixels' is a flat int array [x,y,r,g,b,a, ...] (6 values "
                        + "per pixel). For multi-step painting use om.tex via run_python_script.",
                pixelsArraySchema(),
                args -> editor.setPixels(reqInt(args, "face_id"), parsePixels(args.get("pixels")))));

        registry.register(new McpTool(
                "model_face_fill",
                "Fill a face's texture with a solid RGBA color: the whole texture, or just 'rect' "
                        + "[x,y,w,h] when given — one call, one undo step. color [0,0,0,0] clears "
                        + "to transparent. For shapes/lines/flood fill use om.tex via run_python_script.",
                rgbaSchema()
                        .intg("face_id", "Face identifier")
                        .intArr("rect", "Optional [x,y,w,h] rectangle; omit to fill the whole texture")
                        .required("face_id", "color")
                        .build(),
                args -> {
                    int[] c = reqRgba(args);
                    int faceId = reqInt(args, "face_id");
                    int[] rect = McpArgs.optIntArray(args, "rect");
                    if (rect != null) {
                        if (rect.length != 4) {
                            throw new IllegalArgumentException("rect must be [x,y,w,h]");
                        }
                        return editor.fillRect(faceId, rect[0], rect[1], rect[2], rect[3],
                                c[0], c[1], c[2], c[3]);
                    }
                    if (c[0] == 0 && c[1] == 0 && c[2] == 0 && c[3] == 0) {
                        return editor.clearFace(faceId);
                    }
                    return editor.fillFace(faceId, c[0], c[1], c[2], c[3]);
                }));

        // ---------- Create per-face textures ----------

        registry.register(new McpTool(
                "model_face_create_textures",
                "Allocate a new material + GPU texture (filled with a color) for one or MANY faces "
                        + "that have no per-face mapping yet, making them paintable via "
                        + "model_face_fill/set_pixels or om.tex scripting. Validates the whole batch "
                        + "atomically (rejected if any face is invalid or already has a non-default "
                        + "material), regenerates mesh UVs once, records one undo step. 'faces' is an "
                        + "array of {face_id, width, height, color:[r,g,b,a]} with width/height in "
                        + "[1,1024] (use the suggested sizes from model_face_list_textures detail=true).",
                createTexturesSchema(),
                args -> editor.createFaceTextures(parseCreateSpecs(args.get("faces")))));

        // ---------- Resize ----------

        registry.register(new McpTool(
                "model_face_resize_texture",
                "Resize a face's GPU texture (nearest-neighbor); other faces sharing the material are "
                        + "also affected. Width/height in [1,1024]; sets autoResize=false so the "
                        + "editor won't rescale it on later geometry changes.",
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

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private McpSchema rgbaSchema() {
        return schema().rgba("color", "RGBA [r,g,b,a], each 0..255");
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

    // ===================== list_textures filtering =====================

    /** Compact row for the default {@code model_face_list_textures} response. */
    record FaceRow(int faceId, int materialId, int w, int h, String orientation, String part) {
        static FaceRow from(FaceTextureEditingService.FaceTextureInfo info) {
            return new FaceRow(info.faceId(), info.materialId(),
                    info.textureWidth(), info.textureHeight(),
                    info.orientation(), info.partName());
        }
    }

    /** Paginated wrapper: {@code total} counts post-filter, pre-pagination rows. */
    record FacePage(int total, int offset, List<?> faces) {
    }

    private Object listTextures(JsonNode args) {
        boolean detail = McpArgs.optBool(args, "detail", false);
        String part = McpArgs.optString(args, "part");
        int[] faceIds = McpArgs.optIntArray(args, "face_ids");
        int offset = Math.max(0, McpArgs.optInt(args, "offset", 0));
        int limit = Math.max(1, McpArgs.optInt(args, "limit", 200));

        List<FaceTextureEditingService.FaceTextureInfo> all = editor.listFaceTextures();
        List<FaceTextureEditingService.FaceTextureInfo> filtered = new ArrayList<>();
        for (FaceTextureEditingService.FaceTextureInfo info : all) {
            if (part != null && !part.equals(info.partName())) continue;
            if (faceIds != null && java.util.Arrays.stream(faceIds).noneMatch(id -> id == info.faceId())) continue;
            filtered.add(info);
        }
        List<?> page = filtered.stream()
                .skip(offset)
                .limit(limit)
                .map(info -> detail ? info : FaceRow.from(info))
                .toList();
        return new FacePage(filtered.size(), offset, page);
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
                    intAt(arr, i, "pixels"), intAt(arr, i + 1, "pixels"),
                    intAt(arr, i + 2, "pixels"), intAt(arr, i + 3, "pixels"),
                    intAt(arr, i + 4, "pixels"), intAt(arr, i + 5, "pixels")));
        }
        return out;
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
        return McpArgs.reqRgba(args, "color");
    }
}
