package com.openmason.main.systems.rendering.model.gmr;

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

    @Override
    public void initializeStandardMapping(int triangleCount) {
        if (triangleCount <= 0) {
            triangleToFaceId = null;
            return;
        }

        // 1:1 mapping - each triangle is its own face (default, no assumptions)
        triangleToFaceId = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            triangleToFaceId[t] = t;
        }

        logger.debug("Initialized 1:1 triangle-to-face mapping: {} triangles = {} faces",
            triangleCount, triangleCount);
    }

    @Override
    public void initializeWithTopology(int triangleCount, int trianglesPerFace) {
        if (triangleCount <= 0 || trianglesPerFace <= 0) {
            triangleToFaceId = null;
            return;
        }

        // Explicit topology (opt-in for custom face grouping)
        triangleToFaceId = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            triangleToFaceId[t] = t / trianglesPerFace;
        }

        int faceCount = (triangleCount + trianglesPerFace - 1) / trianglesPerFace;
        logger.debug("Initialized explicit topology mapping: {} triangles, {} tris/face = {} faces",
            triangleCount, trianglesPerFace, faceCount);
    }

    @Override
    public void setMapping(int[] triangleToFaceId) {
        this.triangleToFaceId = triangleToFaceId;
        if (triangleToFaceId != null) {
            logger.debug("Set triangle-to-face mapping: {} triangles", triangleToFaceId.length);
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

        // Find the maximum face ID + 1
        int maxFaceId = 0;
        for (int faceId : triangleToFaceId) {
            if (faceId > maxFaceId) {
                maxFaceId = faceId;
            }
        }
        return maxFaceId + 1;
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
    }
}
