package com.openmason.main.systems.rendering.model.blockmodel;

import org.joml.Vector3f;

/**
 * Utility for calculating face normals and detecting inversions.
 * Used by BlockModelRenderer to maintain correct triangle winding order.
 *
 * Face normals are calculated using the cross product of two edge vectors,
 * following the right-hand rule for counter-clockwise vertex winding.
 * Inversion detection compares current vs original normals via dot product.
 */
public class FaceNormalCalculator {

    /**
     * Calculate normal vector for a face using first 3 vertices.
     * Assumes counter-clockwise winding order when viewed from outside.
     *
     * The normal is computed as: (v1 - v0) × (v2 - v0), which follows
     * the right-hand rule and points outward for CCW winding.
     *
     * @param v0 First vertex position
     * @param v1 Second vertex position
     * @param v2 Third vertex position
     * @return Normalized normal vector pointing outward from the face
     */
    public static Vector3f calculateNormal(Vector3f v0, Vector3f v1, Vector3f v2) {
        if (v0 == null || v1 == null || v2 == null) {
            throw new IllegalArgumentException("Vertex positions cannot be null");
        }

        // Calculate two edge vectors from v0
        Vector3f edge1 = new Vector3f(v1).sub(v0);
        Vector3f edge2 = new Vector3f(v2).sub(v0);

        // Cross product gives normal vector (perpendicular to both edges)
        // Order matters: edge1 × edge2 follows right-hand rule for CCW
        Vector3f normal = edge1.cross(edge2);

        // Normalize to unit length for consistent comparisons
        float length = normal.length();
        if (length < 0.0001f) {
            // Degenerate triangle (collinear vertices) - return arbitrary normal
            return new Vector3f(0, 1, 0);
        }

        return normal.normalize();
    }

    /**
     * Check if a face is inverted compared to original orientation.
     *
     * Inversion is detected by comparing the dot product of current and original
     * normals. If they point in opposite directions (dot < 0), the face is inverted.
     * A small threshold prevents flickering when faces are exactly perpendicular (90°).
     *
     * @param currentNormal Current face normal after deformation
     * @param originalNormal Original face normal at initialization
     * @return true if normals point in opposite directions (face is inverted)
     */
    public static boolean isFaceInverted(Vector3f currentNormal, Vector3f originalNormal) {
        if (currentNormal == null || originalNormal == null) {
            throw new IllegalArgumentException("Normal vectors cannot be null");
        }

        // Dot product > 0: normals point in same general direction (not inverted)
        // Dot product ≈ 0: normals perpendicular (edge case, treat as not inverted)
        // Dot product < 0: normals point in opposite directions (inverted)

        // Use -0.01f threshold to create hysteresis zone and prevent flickering
        // at exactly 90° (where dot = 0 and small numerical errors could flip state)
        return currentNormal.dot(originalNormal) < -0.01f;
    }
}
