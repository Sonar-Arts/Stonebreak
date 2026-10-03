package com.stonebreak.items;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defines how the held tool speeds up breaking blocks of a matching material.
 *
 * <p>Data-driven (SBO 1.9, authored in Open Mason's SBO editor): a tool's item
 * SBO carries a {@code tool} section ({@link SBOFormat.ToolData}: tool class,
 * tier, speed multiplier and the block materials it is effective on), and a
 * block's {@code gameProperties} carry its {@code material} and an optional
 * {@code requiredTier}. A tool speeds a block up when the block's material is
 * in the tool's list and the tool's tier is at least the block's required
 * tier; the block's hardness is then multiplied by the tool's speed multiplier
 * (lower = faster), floored at {@link SBOFormat.ToolData#MIN_EFFECTIVE_HARDNESS}.
 * There is no table in code — a new tool or block only needs its SBO data.
 *
 * <p>The per-frame breaking logic lives in {@code BlockBreaker}; this class
 * stays pure (no GL/world dependencies — lookups go through the headless
 * {@link ItemRegistry} / {@link BlockRegistry}) so it is unit-testable, and
 * {@code hardnessMultiplier(tool, block)} is deterministic.
 */
public final class ToolMiningRules {

    private static final Logger logger = LoggerFactory.getLogger(ToolMiningRules.class);

    /** Per-tool cache; {@code Optional.empty()} = item is not a mining tool. */
    private static final Map<ItemType, Optional<SBOFormat.ToolData>> TOOL_CACHE = new ConcurrentHashMap<>();

    /** Per-block cache; {@code Optional.empty()} = block has no gameProperties. */
    private static final Map<BlockType, Optional<SBOFormat.GameProperties>> BLOCK_CACHE = new ConcurrentHashMap<>();

    private ToolMiningRules() {
    }

    /**
     * Hardness multiplier for breaking {@code block} while holding {@code tool}.
     * Returns {@code 1.0f} when there is no bonus (non-tool, wrong material,
     * tier too low, or unknown tool/block); lower values mean faster breaking.
     */
    public static float hardnessMultiplier(ItemType tool, BlockType block) {
        if (tool == null || block == null) {
            return 1.0f;
        }
        SBOFormat.ToolData data = toolDataFor(tool);
        if (data == null) {
            return 1.0f;
        }
        return data.hardnessMultiplier(materialOf(block), requiredTierOf(block));
    }

    /**
     * Effective hardness of {@code block} under {@code tool}. Convenience for
     * break-progress callers: applies the tool's multiplier with a small floor
     * so a tool never makes a block an instant break.
     */
    public static float effectiveHardness(ItemType tool, BlockType block, float hardness) {
        if (block == null) {
            return hardness;
        }
        return SBOFormat.ToolData.effectiveHardness(
                tool == null ? null : toolDataFor(tool), materialOf(block), requiredTierOf(block), hardness);
    }

    /** The item's authored mining-tool data, or {@code null} when it is not a mining tool. */
    public static SBOFormat.ToolData toolDataFor(ItemType tool) {
        if (tool == null) return null;
        return TOOL_CACHE.computeIfAbsent(tool, ToolMiningRules::lookupTool).orElse(null);
    }

    /** The block's authored material, or {@code null} when it has none. */
    public static String materialOf(BlockType block) {
        return blockProperties(block).map(SBOFormat.GameProperties::material).orElse(null);
    }

    /** Minimum tool tier for a speed-up on {@code block} ({@code 0} = any). */
    public static int requiredTierOf(BlockType block) {
        return blockProperties(block).map(SBOFormat.GameProperties::requiredTier).orElse(0);
    }

    /** Drops the caches (e.g. after the item/block registries are reloaded). */
    public static void invalidate() {
        TOOL_CACHE.clear();
        BLOCK_CACHE.clear();
    }

    private static Optional<SBOFormat.GameProperties> blockProperties(BlockType block) {
        if (block == null) return Optional.empty();
        return BLOCK_CACHE.computeIfAbsent(block, ToolMiningRules::lookupBlock);
    }

    private static Optional<SBOFormat.ToolData> lookupTool(ItemType tool) {
        String objectId = ItemType.objectIdFor(tool);
        if (objectId == null) return Optional.empty();
        try {
            return ItemRegistry.getInstance().get(objectId)
                    .map(e -> e.sboData() != null && e.sboData().manifest() != null
                            ? e.sboData().manifest().tool() : null);
        } catch (RuntimeException ex) {
            logger.warn("Failed to read tool data for {}: {}", tool, ex.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<SBOFormat.GameProperties> lookupBlock(BlockType block) {
        try {
            return BlockRegistry.getInstance().getById(block.getId())
                    .map(BlockRegistry.BlockEntry::properties);
        } catch (RuntimeException ex) {
            logger.warn("Failed to read mining data for {}: {}", block, ex.getMessage());
            return Optional.empty();
        }
    }
}
