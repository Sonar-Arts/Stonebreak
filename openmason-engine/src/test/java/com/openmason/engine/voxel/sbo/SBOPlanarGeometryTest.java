package com.openmason.engine.voxel.sbo;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pane / X detection and grounding: saplings and flowers are built from one or
 * two planes and must stand on the cell floor; cubes are neither.
 */
class SBOPlanarGeometryTest {

    private static final float EPS = 1e-3f;
    private static final Path BLOCKS = Path.of("../stonebreak-game/src/main/resources/sbo/blocks");

    @Test
    void singlePaneIsPlanar() {
        assertTrue(SBOPlanarGeometry.isPlanar(mesh(quadZ(0f, 0f))));
    }

    @Test
    void doubleSidedPaneIsPlanar() {
        assertTrue(SBOPlanarGeometry.isPlanar(mesh(quadZ(0f, 0f), flip(quadZ(0f, 0f)))));
    }

    @Test
    void crossOfTwoPanesIsPlanar() {
        assertTrue(SBOPlanarGeometry.isPlanar(mesh(diagonal(1f), diagonal(-1f))));
    }

    @Test
    void doubleSidedCrossIsPlanar() {
        assertTrue(SBOPlanarGeometry.isPlanar(mesh(diagonal(1f), flip(diagonal(1f)),
                diagonal(-1f), flip(diagonal(-1f)))));
    }

    @Test
    void twoParallelPanesAreNotOnePlane() {
        // Two panes still pass; a third distinct plane does not.
        assertTrue(SBOPlanarGeometry.isPlanar(mesh(quadZ(-0.25f, 0f), quadZ(0.25f, 0f))));
        assertFalse(SBOPlanarGeometry.isPlanar(mesh(quadZ(-0.25f, 0f), quadZ(0f, 0f), quadZ(0.25f, 0f))));
    }

    @Test
    void horizontalPlateIsNotAPane() {
        assertFalse(SBOPlanarGeometry.isPlanar(mesh(axisQuad(1, 0.5f))));
    }

    @Test
    void cubeIsNotPlanar() {
        float[][] faces = new float[6][];
        for (int axis = 0; axis < 3; axis++) {
            faces[axis * 2] = axisQuad(axis, -0.5f);
            faces[axis * 2 + 1] = axisQuad(axis, 0.5f);
        }
        assertFalse(SBOPlanarGeometry.isPlanar(mesh(faces)));
    }

    @Test
    void groundOffsetDropsARaisedPaneToTheFloor() {
        assertEquals(-0.48f, SBOPlanarGeometry.groundOffsetY(mesh(quadZ(0f, 0.48f))), EPS);
        assertEquals(0f, SBOPlanarGeometry.groundOffsetY(mesh(quadZ(0f, 0f))), EPS);
    }

    @Test
    void cypressSaplingIsAnXThatFloatsWithoutGrounding() throws Exception {
        ParsedMeshData sapling = load("SB_Cypress_Sapling.sbo");
        assertTrue(SBOPlanarGeometry.isPlanar(sapling));
        assertEquals(-0.48f, SBOPlanarGeometry.groundOffsetY(sapling), 0.01f);
    }

    @Test
    void roseIsAnXAlreadyOnTheFloor() throws Exception {
        ParsedMeshData rose = load("SB_Rose.sbo");
        assertTrue(SBOPlanarGeometry.isPlanar(rose));
        assertEquals(0f, SBOPlanarGeometry.groundOffsetY(rose), 0.01f);
    }

    // ---- fixtures: each quad is 4 vertices (x,y,z) spanning y -0.5..0.5 before lift ----

    private static ParsedMeshData load(String file) throws Exception {
        Path path = BLOCKS.resolve(file);
        assumeTrue(Files.exists(path), "asset not present: " + path);
        return new SBOParser().parse(path).meshData();
    }

    /** Pane in the plane z = {@code z}, lifted by {@code lift}. */
    private static float[] quadZ(float z, float lift) {
        return new float[]{
                -0.5f, -0.5f + lift, z, 0.5f, -0.5f + lift, z,
                0.5f, 0.5f + lift, z, -0.5f, 0.5f + lift, z};
    }

    /** Diagonal pane along x = {@code sign} * z. */
    private static float[] diagonal(float sign) {
        return new float[]{
                -0.5f, -0.5f, -0.5f * sign, 0.5f, -0.5f, 0.5f * sign,
                0.5f, 0.5f, 0.5f * sign, -0.5f, 0.5f, -0.5f * sign};
    }

    /** Axis-aligned unit face at {@code at} on {@code axis}. */
    private static float[] axisQuad(int axis, float at) {
        float[][] uv = {{-0.5f, -0.5f}, {0.5f, -0.5f}, {0.5f, 0.5f}, {-0.5f, 0.5f}};
        float[] q = new float[12];
        for (int i = 0; i < 4; i++) {
            int u = axis == 0 ? 1 : 0;
            int v = axis == 2 ? 1 : 2;
            q[i * 3 + axis] = at;
            q[i * 3 + u] = uv[i][0];
            q[i * 3 + v] = uv[i][1];
        }
        return q;
    }

    /** Same quad, opposite winding (a back face). */
    private static float[] flip(float[] q) {
        return new float[]{q[0], q[1], q[2], q[9], q[10], q[11], q[6], q[7], q[8], q[3], q[4], q[5]};
    }

    private static ParsedMeshData mesh(float[]... quads) {
        float[] verts = new float[quads.length * 12];
        int[] idx = new int[quads.length * 6];
        for (int i = 0; i < quads.length; i++) {
            System.arraycopy(quads[i], 0, verts, i * 12, 12);
            int b = i * 4;
            System.arraycopy(new int[]{b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, i * 6, 6);
        }
        return new ParsedMeshData(verts, new float[quads.length * 8], idx, null, null);
    }
}
