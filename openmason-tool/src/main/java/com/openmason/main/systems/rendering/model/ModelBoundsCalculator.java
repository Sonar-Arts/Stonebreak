package com.openmason.main.systems.rendering.model;

import org.joml.Vector3f;

/**
 * Computes an axis-aligned bounding box (AABB) from interleaved vertex position data.
 * Stateless utility — all methods are static.
 */
public final class ModelBoundsCalculator {

    private ModelBoundsCalculator() {}

    /**
     * Compute the AABB from an interleaved vertex position array (x, y, z per vertex).
     *
     * @param vertices Vertex positions in [x0, y0, z0, x1, y1, z1, ...] format
     * @return Computed bounds, or {@link ModelBounds#EMPTY} if vertices are null or empty
     */
    public static ModelBounds compute(float[] vertices) {
        if (vertices == null || vertices.length < 3) {
            return ModelBounds.EMPTY;
        }

        float minX = vertices[0], minY = vertices[1], minZ = vertices[2];
        float maxX = minX, maxY = minY, maxZ = minZ;

        for (int i = 3; i < vertices.length; i += 3) {
            float x = vertices[i];
            float y = vertices[i + 1];
            float z = vertices[i + 2];

            if (x < minX) minX = x; else if (x > maxX) maxX = x;
            if (y < minY) minY = y; else if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; else if (z > maxZ) maxZ = z;
        }

        return new ModelBounds(
                new Vector3f(minX, minY, minZ),
                new Vector3f(maxX, maxY, maxZ),
                new Vector3f((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f),
                new Vector3f(maxX - minX, maxY - minY, maxZ - minZ)
        );
    }
}
