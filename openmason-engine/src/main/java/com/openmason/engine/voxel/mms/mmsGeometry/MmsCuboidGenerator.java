package com.openmason.engine.voxel.mms.mmsGeometry;

import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Standard cuboid (box) geometry generator.
 *
 * Generates vertex positions and normals for standard cube-shaped blocks.
 * This is the most common block type in voxel games.
 *
 * Design Philosophy:
 * - DRY: Single implementation for all cube blocks
 * - Performance: Pre-computed face normals
 * - KISS: Simple, well-tested geometry
 *
 * Face Indices:
 * - 0: Top (+Y)
 * - 1: Bottom (-Y)
 * - 2: North (-Z)
 * - 3: South (+Z)
 * - 4: East (+X)
 * - 5: West (-X)
 *
 * @since MMS 1.0
 */
public class MmsCuboidGenerator implements MmsGeometryService {

    /**
     * Per-thread scratch vertex buffer reused across face emissions. Mesh
     * builds happen on worker threads; the consumer copies values into
     * MmsMeshBuilder before the next face is generated, so a single
     * thread-local slot per role is safe. Eliminates ~6 small float[]
     * allocations per visible cube face.
     */
    private static final ThreadLocal<float[]> SCRATCH_VERTICES =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.POSITION_SIZE * MmsBufferLayout.VERTICES_PER_QUAD]);
    private static final ThreadLocal<float[]> SCRATCH_NORMALS =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.NORMAL_SIZE * MmsBufferLayout.VERTICES_PER_QUAD]);

    // Pre-computed face normals (constant across all cubes)
    private static final float[][] FACE_NORMALS = {
        {0, 1, 0},   // Top
        {0, -1, 0},  // Bottom
        {0, 0, -1},  // North
        {0, 0, 1},   // South
        {1, 0, 0},   // East
        {-1, 0, 0}   // West
    };

    /**
     * Face vertex offsets for standard cube.
     * Each face has 4 vertices with (x, y, z) offsets from block origin.
     *
     * CRITICAL: Vertices MUST be in counter-clockwise order when viewed from OUTSIDE.
     * With index pattern [0,1,2] and [0,2,3], the quad is split like this:
     *   v3 --- v2
     *   |  \   |
     *   |   \  |
     *   v0 --- v1
     * Triangle 1: v0->v1->v2 (CCW)
     * Triangle 2: v0->v2->v3 (CCW)
     */
    private static final float[][][] FACE_VERTEX_OFFSETS = {
        // Top face (+Y): Looking down from above (normal points UP)
        // Matches CBR: bottom-left, bottom-right, top-right, top-left
        // In Z-axis terms: far-left, far-right, near-right, near-left (Z+ is "far", Z- is "near" when viewing from above)
        // v0=(0,1,1) v1=(1,1,1) v2=(1,1,0) v3=(0,1,0)
        {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},

        // Bottom face (-Y): Looking up from below (normal points DOWN)
        // Matches CBR: bottom-left, bottom-right, top-right, top-left
        // In Z-axis terms when looking up: near-left, near-right, far-right, far-left
        // v0=(0,0,0) v1=(1,0,0) v2=(1,0,1) v3=(0,0,1)
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},

        // North face (-Z): Looking from outside at z=0 plane (normal points -Z)
        // Matches CBR Back face: When viewed from -Z direction (outside), X direction is reversed
        // v0=(1,0,0) v1=(0,0,0) v2=(0,1,0) v3=(1,1,0)
        {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},

        // South face (+Z): Looking from outside at z=1 plane (normal points +Z)
        // Matches CBR Front face: bottom-left, bottom-right, top-right, top-left
        // v0=(0,0,1) v1=(1,0,1) v2=(1,1,1) v3=(0,1,1)
        {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},

        // East face (+X): Looking from outside at x=1 plane (normal points +X)
        // Matches CBR: bottom-left, bottom-right, top-right, top-left
        // v0=(1,0,1) v1=(1,0,0) v2=(1,1,0) v3=(1,1,1)
        {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},

        // West face (-X): Looking from outside at x=0 plane (normal points -X)
        // Matches CBR: bottom-left, bottom-right, top-right, top-left
        // v0=(0,0,0) v1=(0,0,1) v2=(0,1,1) v3=(0,1,0)
        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}
    };

    @Override
    public float[] generateFaceVertices(int face, float worldX, float worldY, float worldZ) {
        return generateScaledFaceVertices(face, worldX, worldY, worldZ, 1.0f, 1.0f);
    }

    /**
     * Face vertices for a greedy-merged rectangle spanning {@code uScale}
     * blocks along the face's width axis ({@link #uAxis}) and {@code vScale}
     * blocks along its height axis ({@link #vAxis}). Unit scales reproduce
     * {@link #generateFaceVertices} exactly (0/1 offsets times 1.0f are
     * bit-identical), so the two paths cannot drift.
     *
     * @return per-thread scratch array (12 floats) — consume before the next call
     */
    public float[] generateScaledFaceVertices(int face, float worldX, float worldY, float worldZ,
                                              float uScale, float vScale) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }

        float[] vertices = SCRATCH_VERTICES.get();
        float[][] offsets = FACE_VERTEX_OFFSETS[face];
        int uAxis = uAxis(face);
        int vAxis = vAxis(face);

        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            int baseIdx = i * MmsBufferLayout.POSITION_SIZE;
            float ox = offsets[i][0];
            float oy = offsets[i][1];
            float oz = offsets[i][2];
            if (uAxis == 0) {
                ox *= uScale;
            } else if (uAxis == 2) {
                oz *= uScale;
            }
            if (vAxis == 1) {
                oy *= vScale;
            } else if (vAxis == 2) {
                oz *= vScale;
            }

            vertices[baseIdx] = worldX + ox;
            vertices[baseIdx + 1] = worldY + oy;
            vertices[baseIdx + 2] = worldZ + oz;
        }

        return vertices;
    }

    /**
     * The in-plane axis (0=x, 1=y, 2=z) a merged quad's width spans:
     * x for the ±Y and ±Z faces, z for the ±X faces. Matches the
     * {@code MmsGreedyMesher} run-extension axes.
     */
    public static int uAxis(int face) {
        return face >= 4 ? 2 : 0;
    }

    /** The in-plane axis a merged quad's height spans: z for ±Y faces, else y. */
    public static int vAxis(int face) {
        return face <= 1 ? 2 : 1;
    }

    /**
     * The authored 0/1 offset of a face corner along an axis — the single
     * source of truth for face winding, exposed so texture-coordinate scaling
     * can map corners to their in-plane (width, height) position without a
     * second copy of the offsets table.
     */
    public static float cornerOffset(int face, int corner, int axis) {
        return FACE_VERTEX_OFFSETS[face][corner][axis];
    }

    @Override
    public float[] generateFaceNormals(int face) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }

        float[] normals = SCRATCH_NORMALS.get();
        float[] faceNormal = FACE_NORMALS[face];

        // All 4 vertices of a face share the same normal
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            int baseIdx = i * MmsBufferLayout.NORMAL_SIZE;
            normals[baseIdx] = faceNormal[0];
            normals[baseIdx + 1] = faceNormal[1];
            normals[baseIdx + 2] = faceNormal[2];
        }

        return normals;
    }

    /**
     * Validates a face index.
     *
     * @param face Face index to validate
     * @throws IllegalArgumentException if face index is invalid
     */
    protected void validateFaceIndex(int face) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Face index must be 0-5, got: " + face);
        }
    }
}
