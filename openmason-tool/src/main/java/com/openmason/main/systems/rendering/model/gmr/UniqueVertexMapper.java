package com.openmason.main.systems.rendering.model.gmr;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of IUniqueVertexMapper.
 * Maps between mesh vertices and unique geometric positions.
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

    @Override
    public void buildMapping(float[] vertices) {
        if (vertices == null || vertices.length == 0) {
            clear();
            return;
        }

        int meshVertexCount = vertices.length / 3;
        meshToUniqueMapping = new int[meshVertexCount];
        Arrays.fill(meshToUniqueMapping, -1);

        // Temporary list to collect unique positions and their mesh indices
        List<List<Integer>> uniqueGroups = new ArrayList<>();
        List<Integer> representativeIndices = new ArrayList<>();

        for (int meshIdx = 0; meshIdx < meshVertexCount; meshIdx++) {
            float x = vertices[meshIdx * 3];
            float y = vertices[meshIdx * 3 + 1];
            float z = vertices[meshIdx * 3 + 2];

            // Check if this position matches any existing unique vertex
            int matchedUnique = -1;
            for (int u = 0; u < uniqueGroups.size(); u++) {
                int repIdx = representativeIndices.get(u);
                float rx = vertices[repIdx * 3];
                float ry = vertices[repIdx * 3 + 1];
                float rz = vertices[repIdx * 3 + 2];

                float dx = x - rx;
                float dy = y - ry;
                float dz = z - rz;
                if (dx * dx + dy * dy + dz * dz < DEFAULT_EPSILON * DEFAULT_EPSILON) {
                    matchedUnique = u;
                    break;
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
        }

        // Convert to final arrays
        uniqueVertexCount = uniqueGroups.size();
        uniqueVertexIndices = new int[uniqueVertexCount];
        uniqueToMeshIndices = new ArrayList<>(uniqueVertexCount);

        for (int u = 0; u < uniqueVertexCount; u++) {
            uniqueVertexIndices[u] = representativeIndices.get(u);
            List<Integer> group = uniqueGroups.get(u);
            int[] meshIndices = group.stream().mapToInt(Integer::intValue).toArray();
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
