package com.stonebreak.world.fastlod;

/**
 * Chooses a detail level for a chunk based on its Chebyshev distance from the
 * player's chunk. The LOD ring is split into up to
 * {@link FastLodLevel#count()} bands of roughly equal depth; the first band
 * (closest to the loaded area) uses {@link FastLodLevel#finest()} and the
 * outermost uses {@link FastLodLevel#coarsest()}.
 *
 * <p>A narrow <em>preload ring</em> just inside the native render distance
 * also reports {@link FastLodLevel#finest()}. Those nodes are scheduled and
 * uploaded while their chunks are still being drawn natively, so when the
 * player moves and the chunk graduates from native render into the LOD ring
 * the node is already live — no blank tick at the boundary.
 */
public final class FastLodBandPolicy {

    /**
     * Number of chunks inside {@code inner} that we pre-warm with L0 LOD nodes.
     * Must be large enough to cover a player's chunk-per-tick movement with
     * headroom for generation latency; two chunks is enough at normal walking
     * speed and still cheap (only the edge ring is scheduled per tick).
     */
    public static final int PRELOAD_RING = 2;

    private FastLodBandPolicy() {}

    /**
     * @param distance  Chebyshev distance (in chunks) from the player to the node.
     * @param inner     inclusive inner radius (the native render distance in chunks).
     * @param lodRange  total thickness of the LOD ring in chunks.
     * @return the detail level to use, or {@code null} if the chunk is neither
     *         inside the preload ring nor the LOD ring.
     */
    public static FastLodLevel levelFor(int distance, int inner, int lodRange) {
        if (lodRange <= 0) return null;
        int outer = inner + lodRange;
        int preloadInner = Math.max(0, inner - PRELOAD_RING);

        if (distance <= preloadInner || distance > outer) return null;

        // Preload zone — chunks here are covered by native render, but we
        // still build an L0 node so the renderer has a ready-to-draw fallback
        // the instant the chunk leaves the native disk.
        if (distance <= inner) return FastLodLevel.finest();

        int depth = distance - inner - 1;            // 0..lodRange-1
        int bands = FastLodLevel.count();
        int bandWidth = Math.max(1, (lodRange + bands - 1) / bands);
        int idx = Math.min(bands - 1, depth / bandWidth);
        return FastLodLevel.byIndex(idx);
    }
}
