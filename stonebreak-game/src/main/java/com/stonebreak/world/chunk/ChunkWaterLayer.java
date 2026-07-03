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
 *   block != WATER            =&gt;  no entry (enforced by Chunk.setBlock)
 * </pre>
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
        if (value < SOURCE || value > FALLING) {
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
