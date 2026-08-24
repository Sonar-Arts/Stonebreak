package com.openmason.engine.rendering.model.gmr.analysis;

import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer.Orientation;
import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer.PartReport;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindingAnalyzerTest {

    /** Unit cube with all faces wound CCW-from-outside. Face ids 0..5. */
    private static EditableMesh outwardCube() {
        EditableMesh mesh = new EditableMesh();
        int v000 = mesh.addVertex(new Vector3f(0, 0, 0));
        int v100 = mesh.addVertex(new Vector3f(1, 0, 0));
        int v010 = mesh.addVertex(new Vector3f(0, 1, 0));
        int v110 = mesh.addVertex(new Vector3f(1, 1, 0));
        int v001 = mesh.addVertex(new Vector3f(0, 0, 1));
        int v101 = mesh.addVertex(new Vector3f(1, 0, 1));
        int v011 = mesh.addVertex(new Vector3f(0, 1, 1));
        int v111 = mesh.addVertex(new Vector3f(1, 1, 1));
        mesh.addFaceWithId(0, new int[]{v000, v100, v101, v001}); // bottom  (0,-1,0)
        mesh.addFaceWithId(1, new int[]{v010, v011, v111, v110}); // top     (0, 1,0)
        mesh.addFaceWithId(2, new int[]{v000, v010, v110, v100}); // back    (0,0,-1)
        mesh.addFaceWithId(3, new int[]{v001, v101, v111, v011}); // front   (0,0, 1)
        mesh.addFaceWithId(4, new int[]{v000, v001, v011, v010}); // left    (-1,0,0)
        mesh.addFaceWithId(5, new int[]{v100, v110, v111, v101}); // right   ( 1,0,0)
        return mesh;
    }

    private static int[] reversed(int[] loop) {
        int[] out = new int[loop.length];
        for (int i = 0; i < loop.length; i++) {
            out[i] = loop[loop.length - 1 - i];
        }
        return out;
    }

    @Test
    void consistentCubeIsAllOutward() {
        PartReport report = WindingAnalyzer.analyze("cube", outwardCube(), null);
        assertTrue(report.closed());
        assertEquals(6, report.faceCount());
        assertEquals(6, report.outward());
        assertEquals(0, report.inward());
        assertEquals(0, report.indeterminate());
        assertFalse(report.mixedWinding());
        assertNull(report.indeterminateReason());
        assertEquals(1.0f, report.signedVolume(), 1e-4f);
        assertEquals(0, report.invertedFaceIds().length);
        assertTrue(report.hints().isEmpty());
    }

    @Test
    void flippedFaceDetectedAsInward() {
        EditableMesh mesh = outwardCube();
        mesh.replaceFaceLoop(1, reversed(mesh.face(1).loop())); // flip the top
        PartReport report = WindingAnalyzer.analyze("cube", mesh, null);
        assertTrue(report.closed(), "flipped winding must not read as a hole");
        assertEquals(5, report.outward());
        assertEquals(1, report.inward());
        assertTrue(report.mixedWinding());
        assertArrayEquals(new int[]{1}, report.invertedFaceIds());
        assertTrue(report.hints().stream().anyMatch(h -> h.contains("wind inward")));
    }

    @Test
    void fullyInvertedCubeIsAllInward() {
        EditableMesh mesh = outwardCube();
        for (int id = 0; id < 6; id++) {
            mesh.replaceFaceLoop(id, reversed(mesh.face(id).loop()));
        }
        PartReport report = WindingAnalyzer.analyze("cube", mesh, null);
        assertTrue(report.closed());
        assertEquals(0, report.outward());
        assertEquals(6, report.inward());
        assertTrue(report.signedVolume() < 0);
    }

    @Test
    void openPlaneIsIndeterminateNotError() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(1, 0, 0));
        int c = mesh.addVertex(new Vector3f(1, 0, 1));
        int d = mesh.addVertex(new Vector3f(0, 0, 1));
        mesh.addFaceWithId(0, new int[]{a, b, c, d});
        PartReport report = WindingAnalyzer.analyze("pane", mesh, null);
        assertFalse(report.closed());
        assertEquals("open_mesh", report.indeterminateReason());
        assertEquals(1, report.indeterminate());
        assertEquals(0, report.inward());
        assertEquals(0, report.invertedFaceIds().length);
    }

    @Test
    void spriteCrossIsIndeterminateNotError() {
        EditableMesh mesh = new EditableMesh();
        // Two vertical quads crossing in an X (no shared vertices).
        int a0 = mesh.addVertex(new Vector3f(0, 0, 0));
        int a1 = mesh.addVertex(new Vector3f(1, 0, 1));
        int a2 = mesh.addVertex(new Vector3f(1, 1, 1));
        int a3 = mesh.addVertex(new Vector3f(0, 1, 0));
        int b0 = mesh.addVertex(new Vector3f(0, 0, 1));
        int b1 = mesh.addVertex(new Vector3f(1, 0, 0));
        int b2 = mesh.addVertex(new Vector3f(1, 1, 0));
        int b3 = mesh.addVertex(new Vector3f(0, 1, 1));
        mesh.addFaceWithId(0, new int[]{a0, a1, a2, a3});
        mesh.addFaceWithId(1, new int[]{b0, b1, b2, b3});
        PartReport report = WindingAnalyzer.analyze("cross", mesh, null);
        assertEquals("open_mesh", report.indeterminateReason());
        assertEquals(2, report.indeterminate());
        assertEquals(0, report.invertedFaceIds().length);
    }

    @Test
    void openBoxSideIsClassifiedByBboxFlush() {
        // Cube missing its top: open mesh, but the flush bottom face can still
        // be judged against the AABB outward direction.
        EditableMesh mesh = outwardCube();
        mesh.removeFace(1);
        PartReport report = WindingAnalyzer.analyze("openBox", mesh, null);
        assertFalse(report.closed());
        assertEquals("open_mesh", report.indeterminateReason());
        WindingAnalyzer.FaceResult bottom = report.faces().stream()
                .filter(f -> f.faceId() == 0).findFirst().orElseThrow();
        assertEquals(Orientation.OUTWARD, bottom.orientation());
        assertEquals("bbox_flush", bottom.method());
    }

    @Test
    void nonPlanarQuadIsFlagged() {
        EditableMesh mesh = outwardCube();
        // Pull one bottom-face corner well off the plane.
        mesh.setPosition(mesh.face(0).vertexAt(0), new Vector3f(0, 0.4f, 0));
        PartReport report = WindingAnalyzer.analyze("bent", mesh, null);
        assertTrue(java.util.Arrays.stream(report.nonPlanarFaceIds()).anyMatch(id -> id == 0));
    }

    @Test
    void contractViolationCapturedFromSoup() {
        // Two disjoint triangles sharing one face id — not a triangulated
        // simple polygon, so the boundary walk cannot close.
        float[] vertices = {
                0, 0, 0,  1, 0, 0,  0, 1, 0,
                5, 0, 0,  6, 0, 0,  5, 1, 0,
        };
        int[] indices = {0, 1, 2, 3, 4, 5};
        int[] triToFace = {7, 7};
        PartReport report = WindingAnalyzer.analyzeSoup("bad", vertices, indices, triToFace);
        assertArrayEquals(new int[]{7}, report.contractViolationFaceIds());
        assertTrue(report.hints().stream().anyMatch(h -> h.contains("contract")));
    }

    @Test
    void degenerateFaceReported() {
        // Zero-area triangle (collinear points) alongside a valid cube.
        EditableMesh mesh = outwardCube();
        int a = mesh.addVertex(new Vector3f(3, 0, 0));
        int b = mesh.addVertex(new Vector3f(4, 0, 0));
        int c = mesh.addVertex(new Vector3f(5, 0, 0));
        mesh.addFaceWithId(9, new int[]{a, b, c});
        PartReport report = WindingAnalyzer.analyze("degen", mesh, null);
        assertArrayEquals(new int[]{9}, report.degenerateFaceIds());
        WindingAnalyzer.FaceResult degen = report.faces().stream()
                .filter(WindingAnalyzer.FaceResult::degenerate).findFirst().orElseThrow();
        assertNotNull(degen);
        assertEquals(0f, degen.area(), 1e-6f);
    }

    @Test
    void soupRoundTripMatchesDirectMesh() {
        // The cube as OMO-style soup (duplicated corners per face).
        float[][] faceQuads = {
                {0,0,0, 1,0,0, 1,0,1, 0,0,1},
                {0,1,0, 0,1,1, 1,1,1, 1,1,0},
                {0,0,0, 0,1,0, 1,1,0, 1,0,0},
                {0,0,1, 1,0,1, 1,1,1, 0,1,1},
                {0,0,0, 0,0,1, 0,1,1, 0,1,0},
                {1,0,0, 1,1,0, 1,1,1, 1,0,1},
        };
        float[] vertices = new float[6 * 4 * 3];
        int[] indices = new int[6 * 6];
        int[] triToFace = new int[6 * 2];
        for (int f = 0; f < 6; f++) {
            System.arraycopy(faceQuads[f], 0, vertices, f * 12, 12);
            int base = f * 4;
            int ii = f * 6;
            indices[ii] = base;
            indices[ii + 1] = base + 1;
            indices[ii + 2] = base + 2;
            indices[ii + 3] = base;
            indices[ii + 4] = base + 2;
            indices[ii + 5] = base + 3;
            triToFace[f * 2] = f;
            triToFace[f * 2 + 1] = f;
        }
        PartReport report = WindingAnalyzer.analyzeSoup("soupCube", vertices, indices, triToFace);
        assertTrue(report.closed());
        assertEquals(6, report.outward());
        assertEquals(0, report.inward());
    }
}
