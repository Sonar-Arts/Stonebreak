package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optIntArray;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * MCP registrations for the asset-lens ("eyeglass") tools over the game's
 * SBO/SBE folders. Read-only file inspection plus the approval-gated
 * {@code asset_open}.
 */
public final class AssetLensToolDefinitions {

    private final AssetLensService service;
    private final ObjectMapper mapper;

    public AssetLensToolDefinitions(AssetLensService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "asset_list",
                "List/search the game's SBO/SBE assets on disk (blocks, items, mobs, ...). "
                        + "Ranked substring search over objectId and name.",
                McpSchema.of(mapper)
                        .str("query", "Substring of objectId or name; omit for all")
                        .enumStr("kind", "Container filter", "sbo", "sbe")
                        .str("type", "Type filter: block, item, model, mob, clothing, ...")
                        .intg("limit", "Max rows (default 50, cap 200)")
                        .intg("offset", "Pagination offset")
                        .bool("refresh", "Force a folder rescan (default false)")
                        .build(),
                args -> service.list(optString(args, "query"), optString(args, "kind"),
                        optString(args, "type"), optInt(args, "limit", 50),
                        optInt(args, "offset", 0), optBool(args, "refresh", false))));

        registry.register(new McpTool(
                "asset_manifest",
                "Full manifest of one SBO/SBE asset (game properties, recipes, drops, sounds, "
                        + "states/variants inventory).",
                assetSchema().build(),
                args -> service.manifest(reqString(args, "asset"))));

        registry.register(new McpTool(
                "asset_mesh_summary",
                "Mesh overview of an asset's embedded model: parts, counts, bounds, materials, "
                        + "attachment points. Optional state/variant selectors.",
                assetSchema()
                        .str("state", "State name (SBO/SBE); omit for default model")
                        .str("variant", "SBE variant name")
                        .build(),
                args -> service.meshSummary(reqString(args, "asset"),
                        optString(args, "state"), optString(args, "variant"))));

        registry.register(new McpTool(
                "asset_face_data",
                "Per-face geometry of an asset's model: ordered winding loop, geometric "
                        + "normal/orientation, material and UV region. Paginated ("
                        + AssetLensService.FACE_PAGE_DEFAULT + "/page, max "
                        + AssetLensService.FACE_PAGE_MAX + ").",
                assetSchema()
                        .str("state", "State name; omit for default")
                        .str("variant", "SBE variant name")
                        .str("part", "Limit to one part (name or id)")
                        .intArr("face_ids", "Only these face ids")
                        .intg("offset", "Pagination offset")
                        .intg("limit", "Faces per page")
                        .build(),
                args -> service.faceData(reqString(args, "asset"),
                        optString(args, "state"), optString(args, "variant"),
                        optString(args, "part"), optIntArray(args, "face_ids"),
                        optInt(args, "offset", 0), optInt(args, "limit", 0))));

        registry.register(new McpTool(
                "asset_texture_describe",
                "Glyph-grid description of an asset texture (material PNG or flattened default "
                        + "OMT) straight from the file — no editor session needed. Grids over "
                        + AssetLensService.DESCRIBE_MAX_EDGE + "px are downsampled unless rect is given.",
                TextureToolDefinitions.describeSchema(assetSchema()
                        .str("state", "State name; omit for default")
                        .str("variant", "SBE variant name")
                        .intg("material", "Material id (see asset_mesh_summary); omit = default/single"))
                        .build(),
                args -> service.textureDescribe(reqString(args, "asset"),
                        optString(args, "state"), optString(args, "variant"),
                        args.hasNonNull("material") ? args.get("material").asInt() : null,
                        optIntArray(args, "rect"), TextureToolDefinitions.describeOptions(args))));

        registry.register(new McpTool(
                "asset_texture_export",
                "Export an asset texture as PNG into ~/.openmason/exports/<assetId>/ (never into "
                        + "game resources). inline=true returns the image for viewing instead of an ack.",
                assetSchema()
                        .str("state", "State name; omit for default")
                        .str("variant", "SBE variant name")
                        .intg("material", "Material id; omit = default/single")
                        .str("out", "Output file name (sanitized, .png forced)")
                        .bool("inline", "Also return the image content (default false)")
                        .intg("max_size", "Inline longest-side cap, <= "
                                + AssetLensService.INLINE_IMAGE_MAX)
                        .build(),
                args -> service.textureExport(reqString(args, "asset"),
                        optString(args, "state"), optString(args, "variant"),
                        args.hasNonNull("material") ? args.get("material").asInt() : null,
                        optString(args, "out"), optBool(args, "inline", false),
                        optInt(args, "max_size", 0))));

        registry.register(new McpTool(
                "asset_check_winding",
                "Validate an asset model's triangle winding: inverted/degenerate/non-planar faces, "
                        + "face-id contract violations. Open/flat geometry reports indeterminate, "
                        + "never errors.",
                assetSchema()
                        .str("state", "State name; omit for default")
                        .str("variant", "SBE variant name")
                        .str("part", "Limit to one part")
                        .build(),
                args -> service.checkWinding(reqString(args, "asset"),
                        optString(args, "state"), optString(args, "variant"),
                        optString(args, "part"))));

        registry.register(new McpTool(
                "asset_open",
                "Open an asset's embedded model into the editor as an UNSAVED COPY (never edits "
                        + "the SBO/SBE file). Requires the user to approve an in-tool dialog; "
                        + "blocks until answered, declined, or timed out.",
                assetSchema()
                        .str("state", "State model to open; omit for default")
                        .str("variant", "SBE variant model to open")
                        .intg("timeout_seconds", "Wait for the user this long (default 60, max 240)")
                        .build(),
                args -> service.open(reqString(args, "asset"),
                        optString(args, "state"), optString(args, "variant"),
                        optInt(args, "timeout_seconds", 60))));
    }

    private McpSchema assetSchema() {
        return McpSchema.of(mapper)
                .str("asset", "Asset objectId (e.g. 'SB_Oak_Door'); a path inside game resources also works")
                .required("asset");
    }
}
