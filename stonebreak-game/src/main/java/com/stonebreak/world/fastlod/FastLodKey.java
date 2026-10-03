package com.stonebreak.world.fastlod;

/**
 * Identity of a single LOD node: a chunk column at a specific detail level,
 * sampled with or without the cave carve, with or without tree silhouettes.
 * Two nodes with the same key are interchangeable so the manager
 * deduplicates and caches by it.
 *
 * <p>{@code carved} and {@code trees} are part of the identity because they
 * change what the node shows and which cache rows it may reuse: switching the
 * LOD quality preset re-samples the affected levels instead of keeping nodes
 * built for another preset. See {@link FastLodQuality#carves} and
 * {@link FastLodQuality#drawsTrees}. L0 always draws its trees (per cell).
 */
public record FastLodKey(FastLodLevel level, int chunkX, int chunkZ, boolean carved, boolean trees) {

    public FastLodKey {
        if (level == null) throw new IllegalArgumentException("level");
    }

    /** A fully carved node with the classic tree rule (trees at L0 only). */
    public FastLodKey(FastLodLevel level, int chunkX, int chunkZ) {
        this(level, chunkX, chunkZ, true, level == FastLodLevel.L0);
    }

    /** A fully carved node with the classic tree rule (trees at L0 only). */
    public static FastLodKey of(FastLodLevel level, int cx, int cz) {
        return new FastLodKey(level, cx, cz);
    }

    public static FastLodKey of(FastLodLevel level, int cx, int cz, boolean carved) {
        return new FastLodKey(level, cx, cz, carved, level == FastLodLevel.L0);
    }

    public static FastLodKey of(FastLodLevel level, int cx, int cz, boolean carved, boolean trees) {
        return new FastLodKey(level, cx, cz, carved, trees || level == FastLodLevel.L0);
    }

    /** True when this coarse node carries the tree-spot channel. */
    public boolean coarseTrees() {
        return trees && level != FastLodLevel.L0;
    }

    @Override public String toString() {
        return "FastLodKey[L" + level.index() + (carved ? "" : " raw") + (coarseTrees() ? " trees" : "")
            + " " + chunkX + "," + chunkZ + "]";
    }
}
