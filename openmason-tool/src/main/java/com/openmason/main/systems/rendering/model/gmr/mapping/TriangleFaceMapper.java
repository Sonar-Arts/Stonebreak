package com.openmason.main.systems.rendering.model.gmr.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of ITriangleFaceMapper.
 * Tracks which original face each triangle belongs to.
 * Preserves face IDs through subdivision operations.
 *
 * Supports arbitrary geometry
 */
public class TriangleFaceMapper implements ITriangleFaceMapper {

    private static final Logger logger = LoggerFactory.getLogger(TriangleFaceMapper.class);

    private int[] triangleToFaceId;
    private int[] triangleCountPerFace; // Cached: number of triangles per face ID

    @Override
    public void initializeStandardMapping(int triangleCount) {
        if (triangleCount <= 0) {
            triangleToFaceId = null;
            triangleCountPerFace = null;
            return;
        }

        // 1:1 mapping - each triangle is its own face (default, no assumptions)
        triangleToFaceId = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            triangleToFaceId[t] = t;
        }

        rebuildTriangleCountCache();

        logger.debug("Initialized 1:1 triangle-to-face mapping: {} triangles = {} faces",
            triangleCount, triangleCount);
    }

    @Override
    public void initializeWithTopology(int triangleCount, int trianglesPerFace) {
        if (triangleCount <= 0 || trianglesPerFace <= 0) {
            triangleToFaceId = null;
            triangleCountPerFace = null;
            return;
        }

        // Explicit topology (opt-in for custom face grouping)
        triangleToFaceId = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            triangleToFaceId[t] = t / trianglesPerFace;
        }

        rebuildTriangleCountCache();

        int faceCount = (triangleCount + trianglesPerFace - 1) / trianglesPerFace;
        logger.debug("Initialized explicit topology mapping: {} triangles, {} tris/face = {} faces",
            triangleCount, trianglesPerFace, faceCount);
    }

    @Override
    public void setMapping(int[] triangleToFaceId) {
        this.triangleToFaceId = triangleToFaceId;
        if (triangleToFaceId != null) {
            rebuildTriangleCountCache();
            logger.debug("Set triangle-to-face mapping: {} triangles", triangleToFaceId.length);
        } else {
            triangleCountPerFace = null;
        }
    }

    @Override
    public int getOriginalFaceIdForTriangle(int triangleIndex) {
        if (triangleToFaceId == null || triangleIndex < 0 || triangleIndex >= triangleToFaceId.length) {
            return -1;
        }
        return triangleToFaceId[triangleIndex];
    }

    @Override
    public int getOriginalFaceCount() {
        if (triangleToFaceId == null || triangleToFaceId.length == 0) {
            return 0;
        }

        // Count unique face IDs (handles gaps in face indices like Blender)
        java.util.Set<Integer> uniqueFaceIds = new java.util.HashSet<>();
        for (int faceId : triangleToFaceId) {
            uniqueFaceIds.add(faceId);
        }
        return uniqueFaceIds.size();
    }

    @Override
    public boolean hasMapping() {
        return triangleToFaceId != null && triangleToFaceId.length > 0;
    }

    @Override
    public int getTriangleCount() {
        return triangleToFaceId != null ? triangleToFaceId.length : 0;
    }

    @Override
    public int[] getMappingCopy() {
        if (triangleToFaceId == null) {
            return null;
        }
        return triangleToFaceId.clone();
    }

    @Override
    public void clear() {
        triangleToFaceId = null;
        triangleCountPerFace = null;
    }

    @Override
    public int getFaceIdUpperBound() {
        return triangleCountPerFace != null ? triangleCountPerFace.length : 0;
    }

    @Override
    public int getTriangleCountForFace(int faceId) {
        if (triangleCountPerFace == null || faceId < 0 || faceId >= triangleCountPerFace.length) {
            return 0;
        }
        return triangleCountPerFace[faceId];
    }

    /**
     * Rebuild the cached triangle-count-per-face array from the current mapping.
     */
    private void rebuildTriangleCountCache() {
        if (triangleToFaceId == null || triangleToFaceId.length == 0) {
            triangleCountPerFace = null;
            return;
        }

        // Find max face ID to size the array
        int maxFaceId = 0;
        for (int faceId : triangleToFaceId) {
            if (faceId > maxFaceId) {
                maxFaceId = faceId;
            }
        }

        triangleCountPerFace = new int[maxFaceId + 1];
        for (int faceId : triangleToFaceId) {
            if (faceId >= 0) {
                triangleCountPerFace[faceId]++;
            }
        }
    }
}
