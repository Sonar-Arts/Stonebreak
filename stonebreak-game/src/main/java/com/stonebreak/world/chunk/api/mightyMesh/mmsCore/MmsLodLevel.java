package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

/**
 * Mighty Mesh System - Level of Detail enumeration.
 *
 * Defines different mesh complexity levels for distance-based rendering.
 * Higher LOD numbers = lower detail.
 *
 * Design Philosophy:
 * - Performance: Reduce vertex count for distant chunks
 * - Quality: Maintain visual fidelity at appropriate distances
 * - Flexibility: Easy to add/remove LOD levels
 *
 * @since MMS 1.1
 */
public enum MmsLodLevel {
    /**
     * Full detail mesh with all faces.
     * Used for chunks within immediate render distance.
     *
     * - All block faces rendered
     * - Greedy meshing enabled
     * - Distance: 0-4 chunks
     */
    LOD_0(0, 1.0f, 0, 4),

    /**
     * High detail mesh with minor culling.
     * Used for chunks at medium distance.
     *
     * - Small faces culled
     * - Aggressive greedy meshing
     * - Distance: 4-8 chunks
     */
    LOD_1(1, 0.75f, 4, 8),

    /**
     * Medium detail mesh with moderate culling.
     * Used for chunks at far distance.
     *
     * - Only large faces rendered
     * - Very aggressive merging
     * - Distance: 8-16 chunks
     */
    LOD_2(2, 0.5f, 8, 16),

    /**
     * Low detail mesh (silhouette only).
     * Used for very distant chunks.
     *
     * - Only exterior faces rendered
     * - Maximum merging
     * - Distance: 16+ chunks
     */
    LOD_3(3, 0.25f, 16, Integer.MAX_VALUE);

    private final int level;
    private final float detailFactor;
    private final int minDistance;
    private final int maxDistance;

    /**
     * Creates a LOD level.
     *
     * @param level LOD level number (higher = less detail)
     * @param detailFactor Detail factor (0.0 - 1.0)
     * @param minDistance Minimum distance in chunks
     * @param maxDistance Maximum distance in chunks
     */
    MmsLodLevel(int level, float detailFactor, int minDistance, int maxDistance) {
        this.level = level;
        this.detailFactor = detailFactor;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
    }

    /**
     * Gets the LOD level number.
     *
     * @return Level (0 = highest detail)
     */
    public int getLevel() {
        return level;
    }

    /**
     * Gets the detail factor (1.0 = full detail, 0.0 = minimal).
     *
     * @return Detail factor
     */
    public float getDetailFactor() {
        return detailFactor;
    }

    /**
     * Gets the minimum render distance for this LOD.
     *
     * @return Minimum distance in chunks
     */
    public int getMinDistance() {
        return minDistance;
    }

    /**
     * Gets the maximum render distance for this LOD.
     *
     * @return Maximum distance in chunks
     */
    public int getMaxDistance() {
        return maxDistance;
    }

    /**
     * Selects appropriate LOD level based on distance.
     *
     * @param distanceInChunks Distance from camera in chunks
     * @return Appropriate LOD level
     */
    public static MmsLodLevel selectLodForDistance(float distanceInChunks) {
        int dist = (int) distanceInChunks;

        for (MmsLodLevel lod : values()) {
            if (dist >= lod.minDistance && dist < lod.maxDistance) {
                return lod;
            }
        }

        return LOD_3; // Fallback to lowest detail
    }

    /**
     * Calculates distance in chunks from camera to chunk position.
     *
     * @param cameraChunkX Camera chunk X
     * @param cameraChunkZ Camera chunk Z
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return Distance in chunks
     */
    public static float calculateChunkDistance(int cameraChunkX, int cameraChunkZ,
                                               int chunkX, int chunkZ) {
        int dx = chunkX - cameraChunkX;
        int dz = chunkZ - cameraChunkZ;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Checks if this LOD level should cull small faces.
     *
     * @return true if small faces should be culled
     */
    public boolean shouldCullSmallFaces() {
        return level >= 1;
    }

    /**
     * Checks if this LOD level should use aggressive merging.
     *
     * @return true if aggressive merging should be used
     */
    public boolean shouldUseAggressiveMerging() {
        return level >= 1;
    }

    /**
     * Checks if this LOD level should only render exterior faces.
     *
     * @return true if only exterior faces should be rendered
     */
    public boolean shouldRenderExteriorOnly() {
        return level >= 3;
    }

    /**
     * Gets the minimum face size to render (in blocks).
     * Faces smaller than this will be culled.
     *
     * @return Minimum face size
     */
    public int getMinFaceSize() {
        switch (level) {
            case 0: return 1;  // Render all faces
            case 1: return 2;  // Cull 1x1 faces
            case 2: return 4;  // Cull faces < 4 blocks
            case 3: return 8;  // Only large faces
            default: return 1;
        }
    }

    @Override
    public String toString() {
        return String.format("LOD_%d(detail=%.2f, dist=%d-%d)",
            level, detailFactor, minDistance,
            maxDistance == Integer.MAX_VALUE ? -1 : maxDistance);
    }
}
