package com.openmason.main.systems.rendering.model.gmr;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Implementation of IUniqueVertexMapper.
 * Maps between mesh vertices and unique geometric positions using spatial hashing for O(n) performance.
 * For a cube: 24 mesh vertices map to 8 unique positions (3 mesh vertices per corner).
 */
public class UniqueVertexMapper implements IUniqueVertexMapper {

    private static final Logger logger = LoggerFactory.getLogger(UniqueVertexMapper.class);
    private static final float DEFAULT_EPSILON = 0.0001f;

    // For each unique vertex, stores ONE representative mesh index
    // uniqueVertexIndices[uniqueIdx] -> meshIdx (the first mesh vertex at that position)
    private int[] uniqueVertexIndices;

    // For each mesh vertex, stores which unique vertex it belongs to
    // meshToUniqueMapping[meshIdx] -> uniqueIdx
    private int[] meshToUniqueMapping;

    // For each unique vertex, stores ALL mesh indices at that position
    // uniqueToMeshIndices.get(uniqueIdx) -> int[] of all mesh indices
    private List<int[]> uniqueToMeshIndices;

    // Number of unique geometric positions
    private int uniqueVertexCount;

    /**
     * Spatial hash key for efficient vertex lookup.
     * Partitions 3D space into grid cells for O(1) neighbor queries.
     */
    private static class SpatialKey {
        final int x, y, z;

        // Constructor from world position
        SpatialKey(float px, float py, float pz, float cellSize) {
            this.x = (int) Math.floor(px / cellSize);
            this.y = (int) Math.floor(py / cellSize);
            this.z = (int) Math.floor(pz / cellSize);
        }

        // Constructor from grid coordinates (for neighbor lookup)
        SpatialKey(int gridX, int gridY, int gridZ) {
            this.x = gridX;
            this.y = gridY;
            this.z = gridZ;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SpatialKey)) return false;
            SpatialKey key = (SpatialKey) o;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    @Override
    public void buildMapping(float[] vertices) {
        if (vertices == null || vertices.length == 0) {
            clear();
            return;
        }

        // Validate array length
        if (vertices.length % 3 != 0) {
            logger.error("Invalid vertices array length: {} (must be divisible by 3)", vertices.length);
            clear();
            return;
        }

        int meshVertexCount = vertices.length / 3;
        meshToUniqueMapping = new int[meshVertexCount];
        Arrays.fill(meshToUniqueMapping, -1);

        // Temporary lists to collect unique positions and their mesh indices
        List<List<Integer>> uniqueGroups = new ArrayList<>();
        List<Integer> representativeIndices = new ArrayList<>();

        // Spatial hash for O(n) lookup instead of O(n²)
        // Cell size is 2x epsilon to ensure neighboring cells catch all potential matches
        float cellSize = DEFAULT_EPSILON * 2.0f;
        Map<SpatialKey, List<Integer>> spatialHash = new HashMap<>();

        for (int meshIdx = 0; meshIdx < meshVertexCount; meshIdx++) {
            float x = vertices[meshIdx * 3];
            float y = vertices[meshIdx * 3 + 1];
            float z = vertices[meshIdx * 3 + 2];

            SpatialKey baseKey = new SpatialKey(x, y, z, cellSize);

            // Search for matching vertex in this cell and 26 neighboring cells
            int matchedUnique = -1;
            searchLoop:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        SpatialKey neighborKey = new SpatialKey(
                            baseKey.x + dx,
                            baseKey.y + dy,
                            baseKey.z + dz
                        );

                        List<Integer> candidates = spatialHash.get(neighborKey);
                        if (candidates != null) {
                            for (int candidateIdx : candidates) {
                                float cx = vertices[candidateIdx * 3];
                                float cy = vertices[candidateIdx * 3 + 1];
                                float cz = vertices[candidateIdx * 3 + 2];

                                float dx2 = x - cx;
                                float dy2 = y - cy;
                                float dz2 = z - cz;
                                float distSq = dx2 * dx2 + dy2 * dy2 + dz2 * dz2;

                                if (distSq < DEFAULT_EPSILON * DEFAULT_EPSILON) {
                                    matchedUnique = meshToUniqueMapping[candidateIdx];
                                    break searchLoop;
                                }
                            }
                        }
                    }
                }
            }

            if (matchedUnique >= 0) {
                // Add to existing unique group
                uniqueGroups.get(matchedUnique).add(meshIdx);
                meshToUniqueMapping[meshIdx] = matchedUnique;
            } else {
                // Create new unique vertex
                int newUniqueIdx = uniqueGroups.size();
                List<Integer> newGroup = new ArrayList<>();
                newGroup.add(meshIdx);
                uniqueGroups.add(newGroup);
                representativeIndices.add(meshIdx);
                meshToUniqueMapping[meshIdx] = newUniqueIdx;
            }

            // Add current vertex to spatial hash
            spatialHash.computeIfAbsent(baseKey, k -> new ArrayList<>()).add(meshIdx);
        }

        // Convert to final arrays
        uniqueVertexCount = uniqueGroups.size();
        uniqueVertexIndices = new int[uniqueVertexCount];
        uniqueToMeshIndices = new ArrayList<>(uniqueVertexCount);

        for (int u = 0; u < uniqueVertexCount; u++) {
            uniqueVertexIndices[u] = representativeIndices.get(u);
            List<Integer> group = uniqueGroups.get(u);

            // Convert to array without stream overhead
            int[] meshIndices = new int[group.size()];
            for (int i = 0; i < group.size(); i++) {
                meshIndices[i] = group.get(i);
            }
            uniqueToMeshIndices.add(meshIndices);
        }

        logger.debug("Built unique vertex mapping: {} mesh vertices -> {} unique positions",
            meshVertexCount, uniqueVertexCount);
    }

    @Override
    public Vector3f getUniqueVertexPosition(int uniqueIndex, float[] vertices) {
        if (uniqueVertexIndices == null || uniqueIndex < 0 || uniqueIndex >= uniqueVertexCount) {
            return null;
        }
        if (vertices == null) {
            return null;
        }

        int meshIdx = uniqueVertexIndices[uniqueIndex];
        int offset = meshIdx * 3;
        if (offset + 2 >= vertices.length) {
            return null;
        }

        return new Vector3f(
            vertices[offset],
            vertices[offset + 1],
            vertices[offset + 2]
        );
    }

    @Override
    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        if (uniqueToMeshIndices == null || uniqueIndex < 0 || uniqueIndex >= uniqueVertexCount) {
            return new int[0];
        }
        return uniqueToMeshIndices.get(uniqueIndex).clone();
    }

    @Override
    public int getUniqueIndexForMeshVertex(int meshIndex) {
        if (meshToUniqueMapping == null || meshIndex < 0 || meshIndex >= meshToUniqueMapping.length) {
            return -1;
        }
        return meshToUniqueMapping[meshIndex];
    }

    @Override
    public float[] getAllUniqueVertexPositions(float[] vertices) {
        if (uniqueVertexIndices == null || uniqueVertexCount == 0 || vertices == null) {
            return null;
        }

        float[] positions = new float[uniqueVertexCount * 3];
        for (int u = 0; u < uniqueVertexCount; u++) {
            int meshIdx = uniqueVertexIndices[u];
            int srcOffset = meshIdx * 3;
            int dstOffset = u * 3;
            if (srcOffset + 2 < vertices.length) {
                positions[dstOffset] = vertices[srcOffset];
                positions[dstOffset + 1] = vertices[srcOffset + 1];
                positions[dstOffset + 2] = vertices[srcOffset + 2];
            }
        }
        return positions;
    }

    @Override
    public int getUniqueVertexCount() {
        return uniqueVertexCount;
    }

    @Override
    public boolean hasMapping() {
        return uniqueVertexIndices != null && uniqueVertexCount > 0;
    }

    @Override
    public void clear() {
        uniqueVertexIndices = null;
        meshToUniqueMapping = null;
        uniqueToMeshIndices = null;
        uniqueVertexCount = 0;
    }
}
