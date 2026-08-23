package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-vs-material break-speed table: pickaxes on stone-family, axes on
 * wood-family, wooden tier weaker than stone tier, and no bonus for wrong
 * tool kinds / non-tools. Pure logic — no GL/world dependencies.
 */
class ToolMiningRulesTest {

    // ----- No bonus cases. -------------------------------------------------

    @Test
    void nonToolsAndUnknownToolsGetNoBonus() {
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(null, BlockType.STONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.SWORD, BlockType.STONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.BANANA, BlockType.WOOD));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, null));
    }

    @Test
    void wrongToolKindGetsNoBonus() {
        // Axe on stone, pickaxe on wood — materials don't match the tool kind.
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_AXE, BlockType.STONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_AXE, BlockType.COBBLESTONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_PICKAXE, BlockType.WOOD));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, BlockType.WORKBENCH));
        // Dirt/leaves aren't pickaxe or axe material for either tier.
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, BlockType.DIRT));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_AXE, BlockType.LEAVES));
        // A combat axe is not a mining axe.
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WAR_AXE, BlockType.WOOD));
    }

    // ----- Pickaxe material coverage. --------------------------------------

    @Test
    void woodenPickaxeMatchesStoneFamily() {
        for (BlockType stone : stoneFamily()) {
            assertEquals(0.5f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_PICKAXE, stone),
                    "wooden pickaxe should speed up " + stone);
        }
    }

    @Test
    void stonePickaxeMatchesStoneFamilyAndIsFaster() {
        for (BlockType stone : stoneFamily()) {
            assertEquals(0.2f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, stone),
                    "stone pickaxe should speed up " + stone);
            assertTrue(ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, stone)
                            < ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_PICKAXE, stone),
                    "stone pickaxe must beat wooden pickaxe on " + stone);
        }
    }

    // ----- Axe material coverage. ------------------------------------------

    @Test
    void woodenAxeMatchesWoodFamily() {
        for (BlockType wood : woodFamily()) {
            assertEquals(0.5f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_AXE, wood),
                    "wooden axe should speed up " + wood);
        }
    }

    @Test
    void stoneAxeMatchesWoodFamilyAndIsFaster() {
        for (BlockType wood : woodFamily()) {
            assertEquals(0.2f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_AXE, wood),
                    "stone axe should speed up " + wood);
            assertTrue(ToolMiningRules.hardnessMultiplier(ItemType.STONE_AXE, wood)
                            < ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_AXE, wood),
                    "stone axe must beat wooden axe on " + wood);
        }
    }

    // ----- Effective-hardness convenience. ---------------------------------

    @Test
    void effectiveHardnessAppliesTierWithFloor() {
        // Stone hardness 4.0 (matches SBO): wooden 2x -> 2.0s, stone 5x -> 0.8s.
        assertEquals(2.0f, ToolMiningRules.effectiveHardness(ItemType.WOODEN_PICKAXE, BlockType.STONE, 4.0f));
        assertEquals(0.8f, ToolMiningRules.effectiveHardness(ItemType.STONE_PICKAXE, BlockType.STONE, 4.0f));
        // Wood hardness 3.0 (matches SBO): wooden axe 1.5s, stone axe 0.6s.
        assertEquals(1.5f, ToolMiningRules.effectiveHardness(ItemType.WOODEN_AXE, BlockType.WOOD, 3.0f));
        assertEquals(0.6f, ToolMiningRules.effectiveHardness(ItemType.STONE_AXE, BlockType.WOOD, 3.0f));
        // Wrong tool / no tool keeps full hardness.
        assertEquals(4.0f, ToolMiningRules.effectiveHardness(ItemType.STONE_AXE, BlockType.STONE, 4.0f));
        assertEquals(4.0f, ToolMiningRules.effectiveHardness(null, BlockType.STONE, 4.0f));
    }

    @Test
    void effectiveHardnessNeverReachesZero() {
        assertTrue(ToolMiningRules.effectiveHardness(ItemType.STONE_PICKAXE, BlockType.STONE, 0.1f) >= 0.1f);
    }

    private static BlockType[] stoneFamily() {
        return new BlockType[]{
                BlockType.STONE,
                BlockType.COBBLESTONE,
                BlockType.SAND_COBBLESTONE,
                BlockType.RED_SAND_COBBLESTONE,
                BlockType.SANDSTONE,
                BlockType.RED_SANDSTONE,
                BlockType.BRICKS_BLOCK,
                BlockType.STONE_BRICKS,
                BlockType.COAL_ORE,
                BlockType.IRON_ORE,
                BlockType.FURNACE,
                BlockType.CRYSTAL,
                BlockType.MAGMA,
                BlockType.ICE
        };
    }

    private static BlockType[] woodFamily() {
        return new BlockType[]{
                BlockType.WOOD,
                BlockType.PINE,
                BlockType.ELM_WOOD_LOG,
                BlockType.WOOD_PLANKS,
                BlockType.PINE_WOOD_PLANKS,
                BlockType.ELM_WOOD_PLANKS,
                BlockType.WORKBENCH,
                BlockType.OAK_DOOR,
                BlockType.OAK_STAIRS,
                BlockType.ELM_STAIRS,
                BlockType.PINE_STAIRS
        };
    }
}
