package com.openmason.engine.voxel.sbo;

import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometric face classification: which of the six MMS buckets a triangle lands
 * in, whether it sits on a cell boundary, and which way it faces.
 *
 * <p>Both fixtures are built the way the exporter builds them — including the
 * mixed triangle winding several shipped block assets carry — because the
 * whole point of classifying from geometry is that winding cannot be trusted.
 */
class SBONormalComputerTest {

    private static final float EPS = 1e-4f;

    @Test
    void cubeFacesAreAllFlushAndPointOutward() {
        Mesh cube = unitCube(true);
        SBONormalComputer.ProcessedMesh mesh =
                SBONormalComputer.compute(cube.vertices(), cube.texCoords(), cube.indices());

        boolean[] seen = new boolean[6];
        for (int tri = 0; tri < mesh.triangleCount(); tri++) {
            assertTrue(mesh.triangleFlush()[tri], "every cube triangle sits on a cell boundary");
            seen[mesh.triangleFaces()[tri]] = true;
            assertOutward(mesh, tri);
        }
        for (int face = 0; face < 6; face++) {
            assertTrue(seen[face], "no geometry bucketed into face " + face);
        }
    }

    @Test
    void mixedWindingDoesNotFlipCubeNormals() {
        SBONormalComputer.ProcessedMesh consistent = process(unitCube(true));
        SBONormalComputer.ProcessedMesh mixed = process(unitCube(false));

        assertEquals(consistent.triangleCount(), mixed.triangleCount());
        for (int tri = 0; tri < mixed.triangleCount(); tri++) {
            assertEquals(consistent.triangleFaces()[tri], mixed.triangleFaces()[tri],
                    "face bucket must not depend on winding, triangle " + tri);
            for (int c = 0; c < 9; c++) {
                assertEquals(consistent.normals()[tri * 9 + c], mixed.normals()[tri * 9 + c], EPS,
                        "normal must not depend on winding, triangle " + tri);
            }
        }
    }

    @Test
    void stairTreadsAreInteriorAndFaceUp() {
        SBONormalComputer.ProcessedMesh mesh = process(threeStepStair());

        int interiorTops = 0;
        for (int tri = 0; tri < mesh.triangleCount(); tri++) {
            assertOutward(mesh, tri);
            if (mesh.triangleFlush()[tri]) {
                continue;
            }
            if (mesh.triangleFaces()[tri] == SBOFaceConventions.MMS_TOP) {
                interiorTops++;
                // A tread points straight up even though it lives below the
                // block centre, where "point away from the origin" would have
                // turned it upside down.
                assertEquals(1f, mesh.normals()[tri * 9 + 1], EPS);
            }
        }
        // Two treads, two triangles each.
        assertEquals(4, interiorTops, "the two treads below the top slab must be interior +Y faces");
    }

    @Test
    void stairRisersFaceTheOpenSide() {
        SBONormalComputer.ProcessedMesh mesh = process(threeStepStair());

        int interiorRisers = 0;
        for (int tri = 0; tri < mesh.triangleCount(); tri++) {
            if (!mesh.triangleFlush()[tri] && mesh.triangleFaces()[tri] == SBOFaceConventions.MMS_NORTH) {
                interiorRisers++;
                assertEquals(-1f, mesh.normals()[tri * 9 + 2], EPS);
            }
        }
        assertEquals(4, interiorRisers, "the two risers inside the cell must face -Z");
    }

    @Test
    void thinPanelFacesAreClassifiedFromTheSolidSide() {
        // A door-like panel: nothing touches a cell boundary, and the two big
        // faces share a footprint with mirrored triangulation — the case a
        // centroid probe reads inside-out.
        Mesh panel = box(-0.4f, -0.4f, -0.05f, 0.4f, 0.4f, 0.05f, true);
        SBONormalComputer.ProcessedMesh mesh = process(panel);

        for (int tri = 0; tri < mesh.triangleCount(); tri++) {
            assertFalse(mesh.triangleFlush()[tri], "a thin panel touches no cell boundary");
            assertOutward(mesh, tri);
        }
    }

    // ------------------------------------------------------------------

    /** A face normal must point away from the material, i.e. out of the box. */
    private static void assertOutward(SBONormalComputer.ProcessedMesh mesh, int tri) {
        int base = tri * 9;
        float nx = mesh.normals()[base];
        float ny = mesh.normals()[base + 1];
        float nz = mesh.normals()[base + 2];
        assertEquals(1f, Math.abs(nx) + Math.abs(ny) + Math.abs(nz), EPS,
                "axis-aligned geometry must yield a unit axis normal, triangle " + tri);
        int expected = SBOFaceConventions.mmsFaceForAxis(
                Math.abs(nx) > 0.5f ? 0 : Math.abs(ny) > 0.5f ? 1 : 2,
                nx + ny + nz > 0f);
        assertEquals(expected, mesh.triangleFaces()[tri],
                "bucket must agree with the normal, triangle " + tri);
    }

    private static SBONormalComputer.ProcessedMesh process(Mesh m) {
        return SBONormalComputer.compute(m.vertices(), m.texCoords(), m.indices());
    }

    private record Mesh(float[] vertices, float[] texCoords, int[] indices) {}

    private static Mesh unitCube(boolean consistentWinding) {
        return box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, consistentWinding);
    }

    /**
     * Axis-aligned box. With {@code consistentWinding} false, every second face
     * is wound the wrong way round — the shape several shipped assets are in.
     */
    private static Mesh box(float x0, float y0, float z0, float x1, float y1, float z1,
                            boolean consistentWinding) {
        List<float[]> quads = List.of(
                new float[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},  // +Z
                new float[]{x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0},  // -Z
                new float[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},  // -X
                new float[]{x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1},  // +X
                new float[]{x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0},  // +Y
                new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}); // -Y

        List<Float> verts = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int face = 0;
        for (float[] quad : quads) {
            int base = verts.size() / 3;
            for (float f : quad) {
                verts.add(f);
            }
            boolean flip = !consistentWinding && (face % 2 == 1);
            if (flip) {
                indices.addAll(List.of(base, base + 2, base + 1, base, base + 3, base + 2));
            } else {
                indices.addAll(List.of(base, base + 1, base + 2, base, base + 2, base + 3));
            }
            face++;
        }
        return toMesh(verts, indices);
    }

    /**
     * Three-step staircase in a unit cell, ascending toward +Z: the tall slab
     * at +Z, two treads and two risers inside the cell.
     */
    private static Mesh threeStepStair() {
        float h = 0.5f;
        float t = 1f / 6f;   // step top heights: -1/6, +1/6, +1/2
        List<Float> verts = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        // Steps as three stacked slabs; the shared internal walls are simply
        // not emitted, which is exactly how the exporter writes a stair.
        addQuad(verts, indices, -h, -h, -h, h, -h, h);                       // bottom (-Y)
        addQuad(verts, indices, -h, -h, h, h, h, h);                         // +Z slab face
        addQuad(verts, indices, -h, -h, -h, h, -t, -h);                      // -Z lowest riser (flush)
        addQuad(verts, indices, -h, -t, -h, h, -t, -t);                      // tread 1 (interior +Y)
        addQuad(verts, indices, -h, -t, -t, h, t, -t);                       // riser 2 (interior -Z)
        addQuad(verts, indices, -h, t, -t, h, t, t);                         // tread 2 (interior +Y)
        addQuad(verts, indices, -h, t, t, h, h, t);                          // riser 3 (interior -Z)
        addQuad(verts, indices, -h, h, t, h, h, h);                          // top slab (flush +Y)
        addSideProfile(verts, indices, -h, t);                               // -X profile
        addSideProfile(verts, indices, h, t);                                // +X profile
        return toMesh(verts, indices);
    }

    /**
     * Emits an axis-aligned quad from two opposite corners; exactly one of the
     * three axes must be degenerate.
     */
    private static void addQuad(List<Float> verts, List<Integer> indices,
                                float x0, float y0, float z0, float x1, float y1, float z1) {
        int base = verts.size() / 3;
        float[][] corners;
        if (x0 == x1) {
            corners = new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}};
        } else if (y0 == y1) {
            corners = new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
        } else {
            corners = new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}};
        }
        for (float[] c : corners) {
            verts.add(c[0]);
            verts.add(c[1]);
            verts.add(c[2]);
        }
        indices.addAll(List.of(base, base + 1, base + 2, base, base + 2, base + 3));
    }

    /** The stepped silhouette on one side of the staircase, as three quads. */
    private static void addSideProfile(List<Float> verts, List<Integer> indices, float x, float t) {
        float h = 0.5f;
        addQuad(verts, indices, x, -h, -h, x, -t, h);
        addQuad(verts, indices, x, -t, -t, x, t, h);
        addQuad(verts, indices, x, t, t, x, h, h);
    }

    private static Mesh toMesh(List<Float> verts, List<Integer> indices) {
        float[] v = new float[verts.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = verts.get(i);
        }
        int[] idx = new int[indices.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = indices.get(i);
        }
        return new Mesh(v, new float[(v.length / 3) * 2], idx);
    }
}
