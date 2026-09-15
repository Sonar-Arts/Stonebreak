package com.stonebreak.ui.terrainMapper.visualization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every terrain value the mapper has sampled, kept until the mapper is closed, so looking back
 * over ground already seen costs no terrain work at all.
 *
 * <p><b>Why values and not pictures.</b> Producing a value is the expensive part: an uncached
 * one is a diffusion tile on the GPU. Coloring it is a lookup and a blend. So this keeps the
 * values and the picture is redrawn from them on demand — which also lets one sampled column
 * serve every mode and every contour interval, where a stored picture would be one mode at
 * one zoom.
 *
 * <p><b>Levels of detail.</b> Values live on world-aligned power-of-two lattices, 1 to
 * {@link #MAX_SPACING} blocks apart. A view samples the lattice nearest its zoom, and each
 * level is stored in its own fixed-size chunks, so a zoomed-out view fills a handful of coarse
 * chunks instead of thousands of full-detail ones. Because every coarser lattice is a subset of
 * the finer ones, a value is also written into each coarser level it lies on: zooming out over
 * ground seen up close is then already cached. It is deliberately not written into the finer
 * levels — one coarse point would allocate a mostly empty full-detail chunk.
 *
 * <p><b>Memory.</b> Bounded by a chunk budget, evicting the chunks least recently read. Coarse
 * chunks cover so much ground that in practice only full detail is ever evicted.
 *
 * <p>Thread-safe: preview rows are sampled in parallel. Each chunk is guarded by its own
 * monitor; two threads that miss the same column both read the terrain and store equal values,
 * which costs a duplicated tile read rather than a wrong one.
 */
public final class PreviewSampleStore {

    /** Coarsest lattice, in blocks. {@code 1 << MAX_LEVEL}. */
    public static final int MAX_SPACING = 64;
    private static final int MAX_LEVEL = Integer.numberOfTrailingZeros(MAX_SPACING);

    /** Lattice points per chunk side. */
    static final int CHUNK_SIDE = 32;
    private static final int CHUNK_SLOTS = CHUNK_SIDE * CHUNK_SIDE;

    /** Bytes one chunk's values take, for sizing the budget. */
    public static final long CHUNK_BYTES = (long) CHUNK_SLOTS * PreviewChannel.COUNT * Float.BYTES;

    /** Share of the budget freed at once when it overflows, so eviction is not paid per chunk. */
    private static final double EVICT_FRACTION = 0.1;

    private record Key(long seed, int level, int chunkX, int chunkZ) {}

    private static final class Chunk {
        /** Row-major slots, {@link PreviewChannel#COUNT} values each. NaN marks a slot never sampled. */
        final float[] values = new float[CHUNK_SLOTS * PreviewChannel.COUNT];
        volatile long lastUsed;

        Chunk() {
            Arrays.fill(values, Float.NaN);
        }
    }

    private final Map<Key, Chunk> chunks = new ConcurrentHashMap<>();
    private final int maxChunks;
    private final AtomicLong epoch = new AtomicLong();
    private final Object evictLock = new Object();

    public PreviewSampleStore(long budgetBytes) {
        this.maxChunks = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, budgetBytes / CHUNK_BYTES));
    }

    /**
     * Marks the start of a sampling pass. Chunks read during it count as used more recently than
     * anything read before, which is all eviction needs to know — and far cheaper than stamping
     * every read with a clock.
     */
    public void beginPass() {
        epoch.incrementAndGet();
    }

    /**
     * The value of {@code channel} at a lattice point, read from the terrain and cached if this is
     * the first time anything asked for that column at this level of detail.
     *
     * @param spacing a power of two from 1 to {@link #MAX_SPACING}; both coordinates must be
     *                multiples of it
     */
    public float valueAt(long seed, int spacing, int worldX, int worldZ,
                         PreviewChannel channel, TerrainColumns columns) {
        int level = levelOf(spacing);
        Chunk chunk = chunks.get(keyFor(seed, level, worldX, worldZ));
        if (chunk != null) {
            float cached;
            synchronized (chunk) {
                cached = chunk.values[slotOf(level, worldX, worldZ) * PreviewChannel.COUNT + channel.ordinal()];
            }
            if (!Float.isNaN(cached)) {
                chunk.lastUsed = epoch.get();
                return cached;
            }
        }
        float[] column = new float[PreviewChannel.COUNT];
        columns.sample(worldX, worldZ, column);
        store(seed, level, worldX, worldZ, column);
        return column[channel.ordinal()];
    }

    /** Drops everything. Called when the mapper is closed. */
    public void clear() {
        chunks.clear();
    }

    /** Chunks currently held, across every seed and level. */
    public int chunkCount() {
        return chunks.size();
    }

    private void store(long seed, int fromLevel, int worldX, int worldZ, float[] column) {
        long now = epoch.get();
        for (int level = fromLevel; level <= MAX_LEVEL; level++) {
            int mask = (1 << level) - 1;
            if ((worldX & mask) != 0 || (worldZ & mask) != 0) break;
            Chunk chunk = chunks.computeIfAbsent(keyFor(seed, level, worldX, worldZ), k -> new Chunk());
            int base = slotOf(level, worldX, worldZ) * PreviewChannel.COUNT;
            synchronized (chunk) {
                System.arraycopy(column, 0, chunk.values, base, PreviewChannel.COUNT);
            }
            chunk.lastUsed = now;
        }
        if (chunks.size() > maxChunks) {
            evictLeastRecentlyUsed();
        }
    }

    private void evictLeastRecentlyUsed() {
        synchronized (evictLock) {
            int excess = chunks.size() - maxChunks;
            if (excess <= 0) return;
            int toEvict = Math.min(chunks.size(), excess + (int) Math.ceil(maxChunks * EVICT_FRACTION));
            List<Map.Entry<Key, Chunk>> entries = new ArrayList<>(chunks.entrySet());
            entries.sort(Comparator.comparingLong(e -> e.getValue().lastUsed));
            for (int i = 0; i < toEvict; i++) {
                chunks.remove(entries.get(i).getKey(), entries.get(i).getValue());
            }
        }
    }

    private static int levelOf(int spacing) {
        if (spacing < 1 || spacing > MAX_SPACING || Integer.bitCount(spacing) != 1) {
            throw new IllegalArgumentException("spacing must be a power of two in [1, " + MAX_SPACING + "]: " + spacing);
        }
        return Integer.numberOfTrailingZeros(spacing);
    }

    private static Key keyFor(long seed, int level, int worldX, int worldZ) {
        return new Key(seed, level,
                Math.floorDiv(worldX >> level, CHUNK_SIDE),
                Math.floorDiv(worldZ >> level, CHUNK_SIDE));
    }

    private static int slotOf(int level, int worldX, int worldZ) {
        return Math.floorMod(worldZ >> level, CHUNK_SIDE) * CHUNK_SIDE
                + Math.floorMod(worldX >> level, CHUNK_SIDE);
    }
}
