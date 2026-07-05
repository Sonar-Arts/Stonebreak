package com.openmason.engine.rendering.model.gmr.editable;

import org.joml.Vector3f;

/**
 * Fixtures shared by the editable-mesh tests.
 *
 * <p>The cube soup mirrors {@code CubeShape}'s output exactly (24 duplicated
 * corners, 36 indices, 6 quad faces, BL/BR/TR/TL per face, CCW from outside)
 * so import tests exercise the real production layout.
 */
public final class TestMeshes {

    private TestMeshes() {
    }

    /** Unit-cube soup positions, identical layout to {@code CubeShape} (hw=hh=hd=0.5). */
    public static float[] cubeSoupVertices() {
        float h = 0.5f;
        return new float[]{
            // FRONT (+Z)
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
            // BACK (-Z)
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            // LEFT (-X)
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
            // RIGHT (+X)
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            // TOP (+Y)
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            // BOTTOM (-Y)
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
    }

    /** Cube soup indices: two CCW triangles per face, fan from corner 0. */
    public static int[] cubeSoupIndices() {
        int[] indices = new int[36];
        int idx = 0;
        for (int face = 0; face < 6; face++) {
            int base = face * 4;
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;
            indices[idx++] = base + 2;
            indices[idx++] = base + 3;
            indices[idx++] = base;
        }
        return indices;
    }

    /** Cube triangle→face mapping: 12 triangles, 2 per face. */
    public static int[] cubeTriangleToFaceId() {
        int[] mapping = new int[12];
        for (int i = 0; i < 12; i++) {
            mapping[i] = i / 2;
        }
        return mapping;
    }

    /** Imported unit cube: 8 shared vertices, 6 quad faces (ids 0..5). */
    public static EditableMesh cube() {
        return MeshImporter.importSoup(
            cubeSoupVertices(), cubeSoupIndices(), cubeTriangleToFaceId());
    }

    /**
     * A single planar L-shaped (concave) hexagon face in the XY plane, CCW
     * around +Z. Vertex 3 at (1,1) is the reflex corner.
     */
    public static EditableMesh lShapedFace() {
        EditableMesh mesh = new EditableMesh();
        int v0 = mesh.addVertex(new Vector3f(0, 0, 0));
        int v1 = mesh.addVertex(new Vector3f(2, 0, 0));
        int v2 = mesh.addVertex(new Vector3f(2, 1, 0));
        int v3 = mesh.addVertex(new Vector3f(1, 1, 0));
        int v4 = mesh.addVertex(new Vector3f(1, 2, 0));
        int v5 = mesh.addVertex(new Vector3f(0, 2, 0));
        mesh.addFace(new int[]{v0, v1, v2, v3, v4, v5});
        return mesh;
    }

    /** L-shape boundary positions matching {@link #lShapedFace()} (CCW, +Z normal). */
    public static Vector3f[] lShapeLoop() {
        return new Vector3f[]{
            new Vector3f(0, 0, 0),
            new Vector3f(2, 0, 0),
            new Vector3f(2, 1, 0),
            new Vector3f(1, 1, 0),
            new Vector3f(1, 2, 0),
            new Vector3f(0, 2, 0),
        };
    }
}
