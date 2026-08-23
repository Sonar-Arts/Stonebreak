package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;

import java.util.Set;

/**
 * Defines how the held tool speeds up breaking blocks of a matching material.
 *
 * <p>Two orthogonal axes decide the break-speed bonus:
 * <ul>
 *   <li><b>Kind</b> — pickaxes match the stone-family (stone, ores, bricks,
 *       cobblestone, sandstone, crystal, …); axes match the wood-family (logs,
 *       planks, stairs, workbench, door). A tool of the right kind for the
 *       target material gets the speed bonus; anything else breaks at full
 *       hardness.</li>
 *   <li><b>Tier</b> — wooden is the weakest tier; stone breaks the same
 *       materials noticeably faster. The tier factor is a hardness multiplier
 *       (lower = faster); future tiers (iron, diamond, …) just add a row.</li>
 * </ul>
 *
 * <p>The per-frame breaking logic lives in {@code BlockBreaker}; this class
 * stays pure (no GL/world dependencies) so the whole table is unit-testable
 * headless — {@code hardnessMultiplier(tool, block)} is deterministic.
 */
public final class ToolMiningRules {

    /** Wooden tier: 2× faster than hand — deliberately weaker than the old 4× baseline. */
    public static final float WOODEN_TIER_FACTOR = 0.5f;

    /** Stone tier: 5× faster than hand — noticeably stronger than the old 4× baseline. */
    public static final float STONE_TIER_FACTOR = 0.2f;

    /** Blocks a pickaxe mines efficiently (stone / metal-family). */
    private static final Set<BlockType> PICKAXE_MATERIALS = Set.of(
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
    );

    /** Blocks an axe mines efficiently (wood-family). */
    private static final Set<BlockType> AXE_MATERIALS = Set.of(
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
    );

    private ToolMiningRules() {
    }

    /**
     * Hardness multiplier for breaking {@code block} while holding {@code tool}.
     * Returns {@code 1.0f} when there is no bonus (non-tool, wrong tool kind for
     * the material, or unknown tool/block); lower values mean faster breaking.
     */
    public static float hardnessMultiplier(ItemType tool, BlockType block) {
        if (tool == null || block == null) {
            return 1.0f;
        }
        float factor = tierFactor(tool);
        if (factor <= 0.0f) {
            return 1.0f;
        }
        boolean matches = (isPickaxe(tool) && PICKAXE_MATERIALS.contains(block))
                || (isAxe(tool) && AXE_MATERIALS.contains(block));
        return matches ? factor : 1.0f;
    }

    /**
     * Effective hardness of {@code block} under {@code tool}. Convenience for
     * break-progress callers: applies the tier factor with a small floor so a
     * tool never makes a block harder than an instant break (mirrors the
     * historical axe floor).
     */
    public static float effectiveHardness(ItemType tool, BlockType block, float hardness) {
        float multiplier = hardnessMultiplier(tool, block);
        if (multiplier >= 1.0f) {
            return hardness;
        }
        return Math.max(0.1f, hardness * multiplier);
    }

    private static boolean isPickaxe(ItemType tool) {
        return tool == ItemType.WOODEN_PICKAXE || tool == ItemType.STONE_PICKAXE;
    }

    private static boolean isAxe(ItemType tool) {
        return tool == ItemType.WOODEN_AXE || tool == ItemType.STONE_AXE;
    }

    private static float tierFactor(ItemType tool) {
        if (tool == ItemType.WOODEN_PICKAXE || tool == ItemType.WOODEN_AXE) {
            return WOODEN_TIER_FACTOR;
        }
        if (tool == ItemType.STONE_PICKAXE || tool == ItemType.STONE_AXE) {
            return STONE_TIER_FACTOR;
        }
        return 0.0f;
    }
}
