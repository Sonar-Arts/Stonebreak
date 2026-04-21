package com.stonebreak.world.fastlod;

/**
 * Identity of a single LOD node: a chunk column at a specific detail level.
 * Two nodes with the same (level, chunkX, chunkZ) are interchangeable so the
 * manager deduplicates and caches by this triple.
 */
public record FastLodKey(FastLodLevel level, int chunkX, int chunkZ) {

    public FastLodKey {
        if (level == null) throw new IllegalArgumentException("level");
    }

    public static FastLodKey of(FastLodLevel level, int cx, int cz) {
        return new FastLodKey(level, cx, cz);
    }

    @Override public String toString() {
        return "FastLodKey[L" + level.index() + " " + chunkX + "," + chunkZ + "]";
    }
}
