package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optIntArray;
import static com.openmason.main.systems.mcp.McpArgs.optString;

/** Registrations for {@code model_describe} and {@code model_check_winding}. */
public final class ModelInspectionToolDefinitions {

    private final ModelInspectionService service;
    private final ObjectMapper mapper;

    public ModelInspectionToolDefinitions(ModelInspectionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "model_describe",
                "Describe the loaded model like tex_describe describes pixels: totals and bounds "
                        + "(summary), per-part rows (parts), or per-face winding loops, geometric "
                        + "orientation, materials and UVs (faces; paginated). texture:true adds a "
                        + "glyph grid per face (small pages only).",
                TextureToolDefinitions.describeSchema(McpSchema.of(mapper)
                        .enumStr("detail", "Detail level (default summary)",
                                "summary", "parts", "faces")
                        .str("part", "Limit to one part (name or id)")
                        .intArr("face_ids", "Only these face ids (faces detail)")
                        .intg("offset", "Face pagination offset")
                        .intg("limit", "Faces per page (default "
                                + ModelInspectionService.FACE_PAGE_DEFAULT + ", max "
                                + ModelInspectionService.FACE_PAGE_MAX + ")")
                        .bool("texture", "Include per-face texture glyph grids (max "
                                + ModelInspectionService.TEXTURE_PAGE_MAX + " faces/page)"))
                        .build(),
                args -> service.describe(optString(args, "detail"), optString(args, "part"),
                        optIntArray(args, "face_ids"), optInt(args, "offset", 0),
                        optInt(args, "limit", 0), optBool(args, "texture", false),
                        optIntArray(args, "rect"), TextureToolDefinitions.describeOptions(args))));

        registry.register(new McpTool(
                "model_check_winding",
                "Validate the loaded model's winding: inverted/degenerate/non-planar faces and "
                        + "face-id contract violations, per part. Open/flat parts report "
                        + "indeterminate, never errors. Run after topology edits.",
                McpSchema.of(mapper)
                        .str("part", "Limit to one part (name or id)")
                        .build(),
                args -> service.checkWinding(optString(args, "part"))));
    }
}
