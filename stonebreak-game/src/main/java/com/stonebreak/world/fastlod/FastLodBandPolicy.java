package com.stonebreak.world.fastlod;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Chooses a detail level for a chunk based on its Chebyshev distance from the
 * player's chunk. Bands are distance-proportional: level {@code Lk} (cells of
 * {@code 2^k} blocks) is used once the node is at least
 * {@code 2^k × quality.blocksPerCell()} blocks away, so every cell keeps
 * roughly the same on-screen size wherever it is (see {@link FastLodQuality}).
 * The LOD distance only decides where the ring ends — it no longer stretches
 * or squeezes the bands, which made the near ring sharper or blurrier
 * depending on how far the slider was set.
 *
 * <p>A narrow <em>preload ring</em> just inside the native render distance
 * also reports {@link FastLodLevel#finest()}. Those nodes are scheduled and
 * uploaded while their chunks are still being drawn natively, so when the
 * player moves and the chunk graduates from native render into the LOD ring
 * the node is already live — no blank tick at the boundary. The same width
 * just outside the native disk ({@link #HANDOVER_RING}) stays finest too, so
 * the node a chunk hands over to is not immediately swapped for a coarser one.
 */
public final class FastLodBandPolicy {

    /**
     * Number of chunks inside {@code inner} that we pre-warm with L0 LOD nodes.
     * Must be large enough to cover a player's chunk-per-tick movement with
     * headroom for generation latency; two chunks is enough at normal walking
     * speed and still cheap (only the edge ring is scheduled per tick).
     */
    public static final int PRELOAD_RING = 2;

    /**
     * Whether finest nodes cover the whole native disc (the per-column handover of the cubic runtime:
     * anything not READY is drawn by the LOD). {@code -Dstonebreak.lodWholeDisc=off} restores the old
     * edge-only preload ring (holes while cubes stream in).
     */
    private static volatile boolean wholeDisc =
            !"off".equalsIgnoreCase(System.getProperty("stonebreak.lodWholeDisc", "on"));

    /** Whether finest nodes cover the whole native disc (see {@link #setWholeDisc}). */
    public static boolean wholeDisc() {
        return wholeDisc;
    }

    /** Test seam: the pre-cubic edge-only preload ring when false. */
    static void setWholeDisc(boolean on) {
        wholeDisc = on;
    }

    /** Chunks beyond the native disk that stay at the finest level. */
    public static final int HANDOVER_RING = PRELOAD_RING;

    private FastLodBandPolicy() {}

    /**
     * @param distance  Chebyshev distance (in chunks) from the player to the node.
     * @param inner     inclusive inner radius (the native render distance in chunks).
     * @param lodRange  total thickness of the LOD ring in chunks.
     * @param quality   fidelity preset deciding where each level starts.
     * @return the detail level to use, or {@code null} if the chunk is neither
     *         inside the preload ring nor the LOD ring.
     */
    public static FastLodLevel levelFor(int distance, int inner, int lodRange, FastLodQuality quality) {
        if (lodRange <= 0) return null;
        int outer = inner + lodRange;
        // Cubic runtime (2026-11-17): the LOD covers EVERY column that is not READY, inside the native
        // disc too (the renderer hides non-READY detail cubes and fades nodes out per column as their
        // cube columns turn READY) - so the whole disc keeps finest nodes, never only a preload ring.
        int preloadInner = wholeDisc ? -1 : Math.max(0, inner - PRELOAD_RING);

        if (distance <= preloadInner || distance > outer) return null;

        // Preload + handover zones — finest, so the native ↔ LOD swap at the
        // disk edge always meets an L0 node.
        if (distance <= inner + HANDOVER_RING) return FastLodLevel.finest();

        long blocks = (long) distance * WorldConfiguration.CHUNK_SIZE;
        int idx = 0;
        while (idx + 1 < FastLodLevel.count()
                && (long) FastLodLevel.byIndex(idx + 1).cellSize() * quality.blocksPerCell() <= blocks) {
            idx++;
        }
        return FastLodLevel.byIndex(idx);
    }
}
