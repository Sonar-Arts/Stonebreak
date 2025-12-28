package com.openmason.main.systems.rendering.model.gmr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of ITriangleFaceMapper.
 * Tracks which original face (0-5 for cube) each triangle belongs to.
 * Preserves face IDs through subdivision operations.
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

        // For a cube: 12 triangles total (6 faces x 2 triangles each)
        // Triangles 0-1 = face 0, triangles 2-3 = face 1, etc.
        triangleToFaceId = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            triangleToFaceId[t] = t / 2;  // 2 triangles per original face
        }

        logger.debug("Initialized standard triangle-to-face mapping: {} triangles, {} faces",
            triangleCount, (triangleCount + 1) / 2);
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
