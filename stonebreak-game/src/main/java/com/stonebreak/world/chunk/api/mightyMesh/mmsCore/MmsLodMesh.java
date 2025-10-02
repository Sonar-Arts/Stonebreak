package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mighty Mesh System - Multi-LOD mesh container.
 *
 * Holds multiple mesh representations at different detail levels.
 * Enables seamless LOD transitions based on camera distance.
 *
 * Design Philosophy:
 * - Immutable: Thread-safe LOD data
 * - Memory efficient: Only generate needed LOD levels
 * - Performance: Fast LOD selection
 *
 * Usage:
 * <pre>{@code
 * // Create LOD mesh
 * MmsLodMesh lodMesh = MmsLodMesh.builder()
 *     .setLod(LOD_0, fullDetailMesh)
 *     .setLod(LOD_1, mediumDetailMesh)
 *     .build();
 *
 * // Select appropriate LOD for rendering
 * float distance = calculateDistance(camera, chunk);
 * MmsMeshData mesh = lodMesh.selectLod(distance);
 * }</pre>
 *
 * @since MMS 1.1
 */
public final class MmsLodMesh {

    // LOD mesh data storage
    private final Map<MmsLodLevel, MmsMeshData> lodMeshes;
    private final MmsLodLevel highestLod;
    private final MmsLodLevel lowestLod;

    // Metadata
    private final long totalMemoryBytes;
    private final int totalTriangles;

    /**
     * Creates a LOD mesh container (use builder).
     *
     * @param lodMeshes Map of LOD levels to mesh data
     */
    private MmsLodMesh(Map<MmsLodLevel, MmsMeshData> lodMeshes) {
        if (lodMeshes == null || lodMeshes.isEmpty()) {
            throw new IllegalArgumentException("LOD mesh map cannot be null or empty");
        }

        this.lodMeshes = new EnumMap<>(lodMeshes);

        // Find highest and lowest LOD levels
        MmsLodLevel highest = null;
        MmsLodLevel lowest = null;
        for (MmsLodLevel lod : lodMeshes.keySet()) {
            if (highest == null || lod.getLevel() < highest.getLevel()) {
                highest = lod;
            }
            if (lowest == null || lod.getLevel() > lowest.getLevel()) {
                lowest = lod;
            }
        }
        this.highestLod = highest;
        this.lowestLod = lowest;

        // Calculate total memory and triangles
        long memory = 0;
        int triangles = 0;
        for (MmsMeshData mesh : lodMeshes.values()) {
            memory += mesh.getMemoryUsageBytes();
            triangles += mesh.getTriangleCount();
        }
        this.totalMemoryBytes = memory;
        this.totalTriangles = triangles;
    }

    /**
     * Creates a new LOD mesh builder.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Selects the appropriate LOD mesh for a given distance.
     *
     * @param distanceInChunks Distance from camera in chunks
     * @return Mesh data for appropriate LOD level
     */
    public MmsMeshData selectLod(float distanceInChunks) {
        MmsLodLevel selectedLod = MmsLodLevel.selectLodForDistance(distanceInChunks);
        return getLodMesh(selectedLod);
    }

    /**
     * Gets mesh data for a specific LOD level.
     * Falls back to closest available LOD if requested level is not present.
     *
     * @param lod LOD level
     * @return Mesh data for LOD level
     */
    public MmsMeshData getLodMesh(MmsLodLevel lod) {
        // Try exact match
        MmsMeshData mesh = lodMeshes.get(lod);
        if (mesh != null) {
            return mesh;
        }

        // Fall back to closest available LOD
        MmsLodLevel closestLod = findClosestLod(lod);
        return lodMeshes.get(closestLod);
    }

    /**
     * Finds the closest available LOD to the requested level.
     *
     * @param requested Requested LOD level
     * @return Closest available LOD level
     */
    private MmsLodLevel findClosestLod(MmsLodLevel requested) {
        MmsLodLevel closest = highestLod;
        int minDiff = Math.abs(requested.getLevel() - highestLod.getLevel());

        for (MmsLodLevel lod : lodMeshes.keySet()) {
            int diff = Math.abs(requested.getLevel() - lod.getLevel());
            if (diff < minDiff) {
                minDiff = diff;
                closest = lod;
            }
        }

        return closest;
    }

    /**
     * Checks if a specific LOD level is available.
     *
     * @param lod LOD level
     * @return true if available
     */
    public boolean hasLod(MmsLodLevel lod) {
        return lodMeshes.containsKey(lod);
    }

    /**
     * Gets the number of LOD levels available.
     *
     * @return LOD level count
     */
    public int getLodCount() {
        return lodMeshes.size();
    }

    /**
     * Gets the highest detail LOD level available.
     *
     * @return Highest LOD level
     */
    public MmsLodLevel getHighestLod() {
        return highestLod;
    }

    /**
     * Gets the lowest detail LOD level available.
     *
     * @return Lowest LOD level
     */
    public MmsLodLevel getLowestLod() {
        return lowestLod;
    }

    /**
     * Gets the total memory usage across all LOD levels.
     *
     * @return Total memory usage in bytes
     */
    public long getTotalMemoryBytes() {
        return totalMemoryBytes;
    }

    /**
     * Gets the total triangle count across all LOD levels.
     *
     * @return Total triangle count
     */
    public int getTotalTriangles() {
        return totalTriangles;
    }

    /**
     * Checks if this LOD mesh is empty (all levels empty).
     *
     * @return true if all levels are empty
     */
    public boolean isEmpty() {
        for (MmsMeshData mesh : lodMeshes.values()) {
            if (!mesh.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("MmsLodMesh{levels=%d, triangles=%d, memory=%d bytes}",
            getLodCount(), totalTriangles, totalMemoryBytes);
    }

    /**
     * Builder for creating LOD mesh containers.
     */
    public static class Builder {
        private final Map<MmsLodLevel, MmsMeshData> lodMeshes = new EnumMap<>(MmsLodLevel.class);

        /**
         * Sets mesh data for a specific LOD level.
         *
         * @param lod LOD level
         * @param meshData Mesh data
         * @return this builder
         */
        public Builder setLod(MmsLodLevel lod, MmsMeshData meshData) {
            if (lod == null) {
                throw new IllegalArgumentException("LOD level cannot be null");
            }
            if (meshData == null) {
                throw new IllegalArgumentException("Mesh data cannot be null");
            }
            lodMeshes.put(lod, meshData);
            return this;
        }

        /**
         * Removes a LOD level.
         *
         * @param lod LOD level to remove
         * @return this builder
         */
        public Builder removeLod(MmsLodLevel lod) {
            lodMeshes.remove(lod);
            return this;
        }

        /**
         * Clears all LOD levels.
         *
         * @return this builder
         */
        public Builder clear() {
            lodMeshes.clear();
            return this;
        }

        /**
         * Builds the immutable LOD mesh container.
         *
         * @return LOD mesh container
         * @throws IllegalStateException if no LOD levels have been set
         */
        public MmsLodMesh build() {
            if (lodMeshes.isEmpty()) {
                throw new IllegalStateException("At least one LOD level must be set");
            }
            return new MmsLodMesh(lodMeshes);
        }
    }
}
