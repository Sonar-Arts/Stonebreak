package com.stonebreak.items;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.registry.ItemRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the SBO 1.9 mining data across every shipped asset, so a new tool or
 * block can't silently lose its break-speed bonus (the "forgot to register it"
 * failure mode of the old hardcoded tables — see f7ff3e66).
 */
class MiningDataCoverageTest {

    /** Items named like mining tools that are deliberately not mining tools. */
    private static final Set<String> NOT_MINING_TOOLS = Set.of("stonebreak:war_axe");

    @BeforeAll
    static void loadRegistries() {
        BlockRegistry.getInstance().ensureLoaded();
    }

    @Test
    void everyBlockDeclaresAMaterial() {
        List<String> missing = new ArrayList<>();
        for (BlockRegistry.BlockEntry e : BlockRegistry.getInstance().all()) {
            if (e.properties().material() == null) missing.add(e.objectId());
        }
        assertTrue(BlockRegistry.getInstance().size() > 0, "no blocks registered");
        assertEquals(List.of(), missing, "block SBOs without gameProperties.material");
    }

    @Test
    void everyMiningToolNameHasToolData() {
        List<String> missing = new ArrayList<>();
        for (ItemRegistry.ItemEntry e : ItemRegistry.getInstance().all()) {
            String local = e.objectId().substring(e.objectId().indexOf(':') + 1);
            boolean looksLikeTool = local.endsWith("pickaxe") || local.endsWith("axe") || local.endsWith("shovel");
            if (looksLikeTool && !NOT_MINING_TOOLS.contains(e.objectId())
                    && e.sboData().manifest().tool() == null) {
                missing.add(e.objectId());
            }
        }
        assertEquals(List.of(), missing, "tool-named item SBOs without a tool section");
    }

    @Test
    void everyToolMaterialMatchesARealBlock() {
        Set<String> blockMaterials = new TreeSet<>();
        for (BlockRegistry.BlockEntry e : BlockRegistry.getInstance().all()) {
            if (e.properties().material() != null) blockMaterials.add(e.properties().material());
        }
        List<String> dangling = new ArrayList<>();
        for (ItemRegistry.ItemEntry e : ItemRegistry.getInstance().all()) {
            SBOFormat.ToolData tool = e.sboData().manifest().tool();
            if (tool == null) continue;
            assertTrue(!tool.materials().isEmpty(), e.objectId() + " is a tool with no materials");
            for (String m : tool.materials()) {
                if (!blockMaterials.contains(m)) dangling.add(e.objectId() + " → " + m);
            }
        }
        assertEquals(List.of(), dangling, "tool materials no block declares (typo?); known: " + blockMaterials);
    }

    @Test
    void everyAuthoredToolResolvesThroughItemType() {
        for (ItemRegistry.ItemEntry e : ItemRegistry.getInstance().all()) {
            if (e.sboData().manifest().tool() == null) continue;
            ItemType type = ItemType.getByObjectId(e.objectId());
            assertNotNull(type, e.objectId() + " has tool data but no ItemType");
            assertEquals(e.objectId(), ItemType.objectIdFor(type),
                    "objectIdFor must map back, or ToolMiningRules can't find " + e.objectId());
            assertEquals(e.sboData().manifest().tool(), ToolMiningRules.toolDataFor(type));
        }
    }
}
