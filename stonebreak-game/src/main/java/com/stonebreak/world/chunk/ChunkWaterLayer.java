package com.stonebreak.world.chunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.stonebreak.world.chunk.utils.LocalBlockKey;

/**
 * Per-chunk water flow state — the single source of truth for water levels.
 *
 * <p>Stores ONLY non-source water cells, keyed by packed local coordinates
 * ({@link LocalBlockKey}). The invariant, relied on by the sim, mesher, save
 * codec and network codec alike:
 *
 * <pre>
 *   block == WATER, no entry  =&gt;  source (level 0)
 *   entry 1..7                =&gt;  flowing, that level
 *   entry FALLING (8)         =&gt;  falling column (renders full height)
 *   entry RIVER+d (9..16)     =&gt;  a river surface running in octant d
 *   block != WATER            =&gt;  no entry (enforced by Chunk.setBlock)
 * </pre>
 *
 * <p>A RIVER cell is a SOURCE that happens to know which way it runs. Worldgen
 * stamps rivers as source blocks like everything else — the kernel's whole
 * containment design rests on that — so nothing about how this water behaves
 * changes: {@link #level} reports it as a source and the sim, the physics and
 * the save codec all see one. What the marker buys is that the renderer can
 * tell a reach from a pond, which a source block alone cannot say. Use
 * {@link #level} for anything that asks "how much water", and
 * {@link #isRiver}/{@link #octant} for anything that asks "which way".
 *
 * <p>Ocean chunks therefore cost zero bytes, and worldgen water is a source
 * by definition with no seeding pass. {@link ConcurrentHashMap} gives mesh
 * builder threads lock-free reads while the sim thread writes. The layer's
 * lifetime is its chunk's — no unload purging.
 */
public final class ChunkWaterLayer {

    /** Layer value for a falling water cell. Values 1..7 are flowing levels. */
    public static final int FALLING = 8;

    /** Layer value meaning "no entry": source if the block is WATER. */
    public static final int SOURCE = 0;

    public static final int MAX_FLOW_LEVEL = 7;

    /**
     * Base of the river-surface values: {@code RIVER + octant}, the octant
     * being 0..7 of {@code (dx, dz)} with 0 = +X, counter-clockwise in eighths
     * of a turn — the kernel's {@code out_river_flow} encoding, unchanged.
     */
    public static final int RIVER = 9;

    /** Largest value this layer stores. */
    public static final int MAX_VALUE = RIVER + 7;

    /** Whether a layer value marks a river surface. */
    public static boolean isRiver(int value) {
        return value >= RIVER;
    }

    /** The flow octant of a river value; meaningless for any other. */
    public static int octant(int value) {
        return value - RIVER;
    }

    /** The layer value for a river surface running in {@code octant}. */
    public static int river(int octant) {
        return RIVER + octant;
    }

    /**
     * How much water a layer value means, in the 0..8 vocabulary everything
     * that is not the renderer speaks: a river surface is a source.
     */
    public static int level(int value) {
        return isRiver(value) ? SOURCE : value;
    }

    private final ConcurrentHashMap<Integer, Byte> cells = new ConcurrentHashMap<>();

    /** Visitor for {@link #forEach}. Coordinates are chunk-local. */
    @FunctionalInterface
    public interface CellConsumer {
        void accept(int localX, int y, int localZ, int value);
    }

    /**
     * Returns the flow value at the given local cell: {@link #SOURCE} (0) when
     * absent, 1..7 for flowing, {@link #FALLING} (8) for falling. Callers must
     * combine with a block check — 0 only means "source" when the block is WATER.
     */
    public int get(int localX, int y, int localZ) {
        Byte value = cells.get(LocalBlockKey.pack(localX, y, localZ));
        return value == null ? SOURCE : value;
    }

    /**
     * Sets the flow value at the given local cell. {@link #SOURCE} (0) removes
     * the entry (the cell becomes a source if its block is WATER, or simply
     * clean if not).
     */
    public void set(int localX, int y, int localZ, int value) {
        if (value < SOURCE || value > MAX_VALUE) {
            throw new IllegalArgumentException("Water layer value out of range: " + value);
        }
        int key = LocalBlockKey.pack(localX, y, localZ);
        if (value == SOURCE) {
            cells.remove(key);
        } else {
            cells.put(key, (byte) value);
        }
    }

    /** Removes the entry at the given local cell (equivalent to set(.., SOURCE)). */
    public void remove(int localX, int y, int localZ) {
        cells.remove(LocalBlockKey.pack(localX, y, localZ));
    }

    /** Whether the chunk holds no flowing/falling cells (all its water is sources). */
    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Number of flowing/falling cells. */
    public int size() {
        return cells.size();
    }

    /** Removes all entries. Used when re-hydrating a chunk from a save or network snapshot. */
    public void clear() {
        cells.clear();
    }

    /** Visits every flowing/falling cell. Safe to call concurrently with writes. */
    public void forEach(CellConsumer consumer) {
        for (Map.Entry<Integer, Byte> entry : cells.entrySet()) {
            int key = entry.getKey();
            consumer.accept(LocalBlockKey.x(key), LocalBlockKey.y(key), LocalBlockKey.z(key), entry.getValue());
        }
    }
}
