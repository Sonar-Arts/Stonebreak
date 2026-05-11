package com.openmason.main.systems.viewport.viewportRendering.bones;

import org.joml.Vector3f;

/**
 * Static geometry generators for bone gizmo primitives.
 *
 * <p>Two shapes are produced:
 * <ul>
 *   <li><b>Joint octahedron</b> — a unit, centered, 6-vertex 8-triangle octahedron used to
 *       mark a joint position. Caller scales + translates to world space.</li>
 *   <li><b>Bone shaft</b> — a unit-length elongated octahedron spanning {@code y = 0 → y = 1},
 *       with its widest cross-section near {@code y = BONE_HEAD_RATIO}. Caller rotates +Y onto
 *       the parent→child direction and scales by chain length.</li>
 * </ul>
 *
 * Pure data — no OpenGL calls.
 */
final class BoneGizmoGeometry {

    /** Where the bone's widest cross-section sits along its length (Maya-style head fattening). */
    static final float BONE_HEAD_RATIO = 0.18f;

    private BoneGizmoGeometry() {}

    /**
     * Build an interleaved {@code [position(3), color(3)]} vertex buffer for a unit
     * octahedron centered on the origin with given color.
     *
     * @return interleaved float array of length 6 * 6 = 36 floats? No — 6 verts × (3+3) = 36
     */
    static float[] buildJointVertices(Vector3f color) {
        float r = 1.0f; // unit radius — caller scales
        Vector3f[] p = {
                new Vector3f(  r,  0,  0),
                new Vector3f( -r,  0,  0),
                new Vector3f(  0,  r,  0),
                new Vector3f(  0, -r,  0),
                new Vector3f(  0,  0,  r),
                new Vector3f(  0,  0, -r),
        };
        float[] out = new float[p.length * 6];
        for (int i = 0; i < p.length; i++) {
            out[i * 6    ] = p[i].x;
            out[i * 6 + 1] = p[i].y;
            out[i * 6 + 2] = p[i].z;
            out[i * 6 + 3] = color.x;
            out[i * 6 + 4] = color.y;
            out[i * 6 + 5] = color.z;
        }
        return out;
    }

    /** Triangle indices for the joint octahedron (8 tris, 24 indices). */
    static int[] jointIndices() {
        return new int[]{
                // +X cap
                0, 2, 4,
                0, 4, 3,
                0, 3, 5,
                0, 5, 2,
                // -X cap
                1, 4, 2,
                1, 3, 4,
                1, 5, 3,
                1, 2, 5
        };
    }

    /**
     * Build the bone shaft geometry: 6 vertices forming an elongated bipyramid spanning
     * {@code y ∈ [0, 1]} with its widest section near {@link #BONE_HEAD_RATIO}.
     *
     * @param color baked vertex color
     * @param halfWidth half-extent of the bone's widest section (X/Z)
     */
    static float[] buildShaftVertices(Vector3f color, float halfWidth) {
        Vector3f[] p = {
                new Vector3f(0,           0,                  0),                 // head (parent joint)
                new Vector3f(0,           1,                  0),                 // tail (child joint)
                new Vector3f( halfWidth,  BONE_HEAD_RATIO,    0),                 // +X belt
                new Vector3f(-halfWidth,  BONE_HEAD_RATIO,    0),                 // -X belt
                new Vector3f(0,           BONE_HEAD_RATIO,     halfWidth),        // +Z belt
                new Vector3f(0,           BONE_HEAD_RATIO,    -halfWidth),        // -Z belt
        };
        float[] out = new float[p.length * 6];
        for (int i = 0; i < p.length; i++) {
            out[i * 6    ] = p[i].x;
            out[i * 6 + 1] = p[i].y;
            out[i * 6 + 2] = p[i].z;
            out[i * 6 + 3] = color.x;
            out[i * 6 + 4] = color.y;
            out[i * 6 + 5] = color.z;
        }
        return out;
    }

    /** Triangle indices for the bone shaft (8 tris, 24 indices). */
    static int[] shaftIndices() {
        return new int[]{
                // Head (cap toward parent)
                0, 2, 4,
                0, 4, 3,
                0, 3, 5,
                0, 5, 2,
                // Tail (cap toward child)
                1, 4, 2,
                1, 3, 4,
                1, 5, 3,
                1, 2, 5
        };
    }
}
