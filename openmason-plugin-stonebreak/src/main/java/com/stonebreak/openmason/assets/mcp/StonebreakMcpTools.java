package com.stonebreak.openmason.assets.mcp;

import com.openmason.api.knowledge.GuideTopic;
import com.openmason.api.knowledge.ResourceText;
import com.openmason.api.mcp.McpToolContribution;
import com.openmason.main.systems.mcp.McpSharedServices;

import java.util.List;

/**
 * The Stonebreak plugin's MCP tool groups and guide topic (#431; they were built into the tool,
 * registered by the plugin since #382): asset export/editor tools ({@code sbo_export},
 * {@code sbe_export}, {@code sbo_editor_*}, {@code sbe_editor_*}) and the read-only asset lens
 * ({@code asset_*}) over the game target's shipped SBO/SBE files.
 */
public final class StonebreakMcpTools {

    private StonebreakMcpTools() {
    }

    /** {@code stonebreak.assets}: SBO/SBE export and the SBO/SBE editor seams. */
    public static final McpToolContribution ASSET_EXPORT = new McpToolContribution("stonebreak.assets", c -> {
        McpSharedServices s = McpSharedServices.of(c);
        new AssetExportToolDefinitions(new AssetExportService(s.editor(), s.writes(), s.modelFiles(), s.texture()),
                c.mapper()).registerAll(c.registry());
    });

    /** {@code stonebreak.lens}: read-only views of shipped assets. */
    public static final McpToolContribution ASSET_LENS = new McpToolContribution("stonebreak.lens",
            c -> new AssetLensToolDefinitions(new AssetLensService(McpSharedServices.of(c).editor(), c.mapper()),
                    c.mapper()).registerAll(c.registry()));

    /** Both groups, in registration order. */
    public static List<McpToolContribution> all() {
        return List.of(ASSET_EXPORT, ASSET_LENS);
    }

    /** The {@code assets} guide topic (the opener is written here: plugin resources are encapsulated). */
    public static GuideTopic assetsTopic() {
        return new GuideTopic("assets",
                ResourceText.of(StonebreakMcpTools.class::getResourceAsStream, "/openmason/guide/assets.md"));
    }
}
