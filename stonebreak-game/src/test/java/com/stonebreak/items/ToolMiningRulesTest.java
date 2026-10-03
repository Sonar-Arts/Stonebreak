package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-vs-material break speed, sourced from the shipped SBO data (item
 * {@code tool} sections + block {@code gameProperties.material}): pickaxes on
 * stone/ore/crystal/ice, axes on wood, shovels on dirt/sand/gravel/clay/snow,
 * wooden tier weaker than stone tier, and no bonus for wrong tool kinds or
 * non-tools. Pure logic — no GL/world dependencies.
 */
class ToolMiningRulesTest {

    private static final float WOODEN = 0.5f;
    private static final float STONE = 0.2f;

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
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_AXE, BlockType.STONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_AXE, BlockType.COBBLESTONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_PICKAXE, BlockType.WOOD));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, BlockType.WORKBENCH));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_PICKAXE, BlockType.DIRT));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_SHOVEL, BlockType.STONE));
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WOODEN_SHOVEL, BlockType.WOOD));
        // Leaves have a material, but no tool lists it.
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.STONE_AXE, BlockType.LEAVES));
        // A combat axe is not a mining axe.
        assertEquals(1.0f, ToolMiningRules.hardnessMultiplier(ItemType.WAR_AXE, BlockType.WOOD));
    }

    // ----- Per-family coverage. --------------------------------------------

    @Test
    void pickaxesMatchStoneFamilyAndStoneTierIsFaster() {
        assertFamily(ItemType.WOODEN_PICKAXE, ItemType.STONE_PICKAXE, stoneFamily());
    }

    @Test
    void axesMatchWoodFamilyAndStoneTierIsFaster() {
        assertFamily(ItemType.WOODEN_AXE, ItemType.STONE_AXE, woodFamily());
    }

    @Test
    void shovelsMatchDirtFamilyAndStoneTierIsFaster() {
        assertFamily(ItemType.WOODEN_SHOVEL, ItemType.STONE_SHOVEL, dirtFamily());
    }

    // ----- Effective-hardness convenience. ---------------------------------

    @Test
    void effectiveHardnessAppliesTierWithFloor() {
        // Stone hardness 4.0 (matches SBO): wooden 2x -> 2.0s, stone 5x -> 0.8s.
        assertEquals(2.0f, ToolMiningRules.effectiveHardness(ItemType.WOODEN_PICKAXE, BlockType.STONE, 4.0f));
        assertEquals(0.8f, ToolMiningRules.effectiveHardness(ItemType.STONE_PICKAXE, BlockType.STONE, 4.0f));
        // Wood hardness 3.0 (matches SBO): wooden axe 1.5s, stone axe 0.6s.
        assertEquals(1.5f, ToolMiningRules.effectiveHardness(ItemType.WOODEN_AXE, BlockType.WOOD, 3.0f));
        assertEquals(0.6f, ToolMiningRules.effectiveHardness(ItemType.STONE_AXE, BlockType.WOOD, 3.0f), 1e-6f);
        // Dirt hardness 2.0 (matches SBO): wooden shovel 1.0s, stone shovel 0.4s.
        assertEquals(1.0f, ToolMiningRules.effectiveHardness(ItemType.WOODEN_SHOVEL, BlockType.DIRT, 2.0f));
        assertEquals(0.4f, ToolMiningRules.effectiveHardness(ItemType.STONE_SHOVEL, BlockType.DIRT, 2.0f), 1e-6f);
        // Wrong tool / no tool keeps full hardness.
        assertEquals(4.0f, ToolMiningRules.effectiveHardness(ItemType.STONE_AXE, BlockType.STONE, 4.0f));
        assertEquals(4.0f, ToolMiningRules.effectiveHardness(null, BlockType.STONE, 4.0f));
    }

    @Test
    void effectiveHardnessNeverReachesZero() {
        assertTrue(ToolMiningRules.effectiveHardness(ItemType.STONE_PICKAXE, BlockType.STONE, 0.1f) >= 0.1f);
        assertEquals(0.1f, ToolMiningRules.effectiveHardness(ItemType.STONE_SHOVEL, BlockType.SNOW, 0.1f));
    }

    @Test
    void unbreakableStaysUnbreakable() {
        assertEquals(Float.POSITIVE_INFINITY,
                ToolMiningRules.effectiveHardness(ItemType.STONE_PICKAXE, BlockType.BEDROCK, Float.POSITIVE_INFINITY));
    }

    private static void assertFamily(ItemType wooden, ItemType stone, BlockType[] family) {
        for (BlockType block : family) {
            assertEquals(WOODEN, ToolMiningRules.hardnessMultiplier(wooden, block),
                    wooden + " should speed up " + block);
            assertEquals(STONE, ToolMiningRules.hardnessMultiplier(stone, block),
                    stone + " should speed up " + block);
        }
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
                BlockType.LIMESTONE,
                BlockType.LIMESTONE_STALAGMITE,
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

    private static BlockType[] dirtFamily() {
        return new BlockType[]{
                BlockType.DIRT,
                BlockType.GRASS,
                BlockType.SNOWY_DIRT,
                BlockType.CLAY,
                BlockType.SAND,
                BlockType.RED_SAND,
                BlockType.GRAVEL,
                BlockType.SNOW
        };
    }
}
