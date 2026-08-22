package com.stonebreak.blocks.drops;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-driven block drops: looks up the optional SBO 1.8 {@code drops} table of
 * a {@link BlockType} (authored in Open Mason's SBO editor) and rolls it into
 * concrete {@code (item, count)} stacks.
 *
 * <p>Renderer-free by design — resolution goes through {@link BlockRegistry},
 * not the client's {@code SBOBlockBridge} — because drops are spawned on the
 * server thread (see {@code DropUtil.handleBlockBroken}).
 *
 * <p>Semantics (mirrors {@link SBOFormat.DropData}): a block with no table
 * yields {@code null} from {@link #tableFor}, meaning "use the built-in rule";
 * a present table is authoritative (empty = drops nothing). The breaking
 * tool's {@code objectId} selects a per-tool override, otherwise the default
 * list applies. Drop lines naming an objectId that resolves to neither a block
 * nor an item are skipped with a warning (once per block).
 */
public final class BlockDropTables {

    private static final Logger logger = LoggerFactory.getLogger(BlockDropTables.class);

    /** One rolled drop line: what and how many (always {@code count > 0}). */
    public record RolledDrop(Item item, int count) {}

    /** Per-block cache; {@code Optional.empty()} = block has no table. */
    private static final Map<BlockType, Optional<SBOFormat.DropData>> CACHE = new ConcurrentHashMap<>();

    private BlockDropTables() {}

    /** The block's authored drop table, or {@code null} when its SBO has none. */
    public static SBOFormat.DropData tableFor(BlockType block) {
        if (block == null) return null;
        return CACHE.computeIfAbsent(block, BlockDropTables::lookup).orElse(null);
    }

    /** True when the block's SBO carries a drop table (even an empty one). */
    public static boolean hasTable(BlockType block) {
        return tableFor(block) != null;
    }

    /**
     * Rolls the block's table for the given tool. Returns {@code null} when the
     * block has no table (caller falls back to its built-in rule); otherwise a
     * possibly-empty list of drops.
     */
    public static List<RolledDrop> roll(BlockType block, ItemType tool, Random random) {
        SBOFormat.DropData table = tableFor(block);
        if (table == null) return null;
        return roll(table, ItemType.objectIdFor(tool), random);
    }

    /**
     * Pure roll of a table: applies the tool override selection, each line's
     * chance and count range, and resolves objectIds to game items.
     */
    public static List<RolledDrop> roll(SBOFormat.DropData table, String toolObjectId, Random random) {
        List<SBOFormat.DropEntry> lines = table.dropsFor(toolObjectId);
        List<RolledDrop> out = new ArrayList<>(lines.size());
        for (SBOFormat.DropEntry line : lines) {
            if (line.chance() < 1f && random.nextFloat() >= line.chance()) continue;
            int span = line.maxCount() - line.minCount() + 1;
            int count = line.minCount() + (span > 1 ? random.nextInt(span) : 0);
            if (count <= 0) continue;
            Item item = resolveItem(line.objectId());
            if (item == null) {
                logger.warn("Drop line references unknown objectId '{}' — skipped", line.objectId());
                continue;
            }
            out.add(new RolledDrop(item, count));
        }
        return out;
    }

    /** Resolves a drop-line objectId to a block or item; {@code null} when unknown. */
    public static Item resolveItem(String objectId) {
        BlockType bt = BlockType.getByObjectId(objectId);
        if (bt != null) return bt;
        return ItemType.getByObjectId(objectId);
    }

    /** Drops the cache (e.g. after the block registry is reloaded). */
    public static void invalidate() {
        CACHE.clear();
    }

    private static Optional<SBOFormat.DropData> lookup(BlockType block) {
        try {
            return BlockRegistry.getInstance().getById(block.getId())
                    .map(e -> e.sboData() != null && e.sboData().manifest() != null
                            ? e.sboData().manifest().drops() : null);
        } catch (RuntimeException ex) {
            logger.warn("Failed to read drop table for {}: {}", block, ex.getMessage());
            return Optional.empty();
        }
    }
}
