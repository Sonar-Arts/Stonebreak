package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshInvariants;
import com.openmason.engine.rendering.model.gmr.editable.PolygonTriangulator;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link ScaleFacesOp}, {@link InsetFacesOp} and {@link ExtrudeFacesOp}. */
class FaceShapingOpsTest {

    /** Unit cube: 8 shared vertices, 6 quads, ids 0..5 (front face id 0 at +Z). */
    private static EditableMesh cube() {
        EditableMesh m = new EditableMesh();
        float h = 0.5f;
        int v000 = m.addVertex(new Vector3f(-h, -h, -h));
        int v100 = m.addVertex(new Vector3f( h, -h, -h));
        int v110 = m.addVertex(new Vector3f( h,  h, -h));
        int v010 = m.addVertex(new Vector3f(-h,  h, -h));
        int v001 = m.addVertex(new Vector3f(-h, -h,  h));
        int v101 = m.addVertex(new Vector3f( h, -h,  h));
        int v111 = m.addVertex(new Vector3f( h,  h,  h));
        int v011 = m.addVertex(new Vector3f(-h,  h,  h));
        m.addFace(new int[]{v001, v101, v111, v011}); // 0: front  (+Z)
        m.addFace(new int[]{v100, v000, v010, v110}); // 1: back   (-Z)
        m.addFace(new int[]{v000, v001, v011, v010}); // 2: left   (-X)
        m.addFace(new int[]{v101, v100, v110, v111}); // 3: right  (+X)
        m.addFace(new int[]{v011, v111, v110, v010}); // 4: top    (+Y)
        m.addFace(new int[]{v000, v100, v101, v001}); // 5: bottom (-Y)
        return m;
    }

    private static Vector3f faceNormal(EditableMesh mesh, int faceId) {
        EditableFace face = mesh.face(faceId);
        Vector3f[] loop = new Vector3f[face.loopLength()];
        for (int i = 0; i < loop.length; i++) {
            loop[i] = mesh.position(face.vertexAt(i));
        }
        return PolygonTriangulator.newellNormal(loop).normalize();
    }

    // ── ScaleFacesOp ────────────────────────────────────────────────────────

    @Test
    void scaleSingleFaceMovesItsFourVerticesAboutItsCentroid() {
        EditableMesh mesh = cube();
        ScaleFacesOp.Result result = ScaleFacesOp.apply(mesh, new int[]{0}, 0.5f, null);

        assertEquals(4, result.movedVertexIds().length);
        // Front face at z=+0.5: centroid (0,0,0.5); x/y shrink to ±0.25, z unchanged.
        for (int v : result.movedVertexIds()) {
            Vector3f p = mesh.position(v);
            assertEquals(0.25f, Math.abs(p.x), 1e-6f);
            assertEquals(0.25f, Math.abs(p.y), 1e-6f);
            assertEquals(0.5f, p.z, 1e-6f);
        }
        // Topology untouched.
        assertEquals(6, mesh.faceCount());
        assertEquals(8, mesh.vertexCount());
        MeshInvariants.assertClosedManifold(mesh);
    }

    @Test
    void scaleUsesExplicitPivotWhenGiven() {
        EditableMesh mesh = cube();
        // Pivot at a front-face corner: that corner must not move.
        Vector3f pivot = mesh.position(4); // (-0.5, -0.5, 0.5)
        ScaleFacesOp.apply(mesh, new int[]{0}, 2.0f, pivot);
        assertTrue(mesh.position(4).equals(pivot, 1e-6f));
    }

    @Test
    void scaleInvalidSelectionReturnsNull() {
        EditableMesh mesh = cube();
        assertNull(ScaleFacesOp.apply(mesh, new int[]{99}, 2.0f, null));
        assertNull(ScaleFacesOp.apply(mesh, null, 2.0f, null));
    }

    // ── InsetFacesOp ────────────────────────────────────────────────────────

    @Test
    void insetSingleCubeFace() {
        EditableMesh mesh = cube();
        List<InsetFacesOp.FaceResult> results = InsetFacesOp.apply(mesh, new int[]{0}, 0.1f);

        assertEquals(1, results.size());
        InsetFacesOp.FaceResult result = results.get(0);
        assertEquals(0, result.faceId());
        assertEquals(4, result.borderFaceIds().length);

        assertEquals(12, mesh.vertexCount(), "V+4");
        assertEquals(10, mesh.faceCount(), "F+4");
        MeshInvariants.assertValid(mesh);
        MeshInvariants.assertClosedManifold(mesh);

        // Inner cap keeps the face id, shrunk by exactly the inset amount.
        EditableFace cap = mesh.face(0);
        assertEquals(4, cap.loopLength());
        for (int i = 0; i < 4; i++) {
            Vector3f p = mesh.position(cap.vertexAt(i));
            assertEquals(0.4f, Math.abs(p.x), 1e-5f, "even thickness on x");
            assertEquals(0.4f, Math.abs(p.y), 1e-5f, "even thickness on y");
            assertEquals(0.5f, p.z, 1e-6f, "cap stays in the face plane");
        }

        // Cap and border quads all face outward (+Z).
        assertTrue(faceNormal(mesh, 0).z > 0.99f);
        for (int borderId : result.borderFaceIds()) {
            assertTrue(faceNormal(mesh, borderId).z > 0.99f,
                "border quad " + borderId + " wound with the face normal");
        }
    }

    @Test
    void insetIsEvenThicknessOnElongatedFace() {
        // 2x1 rectangle in the XY plane: a centroid-lerp inset would inset
        // twice as far on the long axis; even thickness must not.
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(2, 0, 0));
        int c = mesh.addVertex(new Vector3f(2, 1, 0));
        int d = mesh.addVertex(new Vector3f(0, 1, 0));
        int faceId = mesh.addFace(new int[]{a, b, c, d});

        InsetFacesOp.apply(mesh, new int[]{faceId}, 0.2f);

        EditableFace cap = mesh.face(faceId);
        for (int i = 0; i < 4; i++) {
            Vector3f p = mesh.position(cap.vertexAt(i));
            assertTrue(Math.abs(p.x - 0.2f) < 1e-5f || Math.abs(p.x - 1.8f) < 1e-5f,
                "x inset by exactly 0.2: " + p.x);
            assertTrue(Math.abs(p.y - 0.2f) < 1e-5f || Math.abs(p.y - 0.8f) < 1e-5f,
                "y inset by exactly 0.2: " + p.y);
        }
        MeshInvariants.assertValid(mesh);
    }

    @Test
    void insetConcaveFaceKeepsInvariants() {
        EditableMesh mesh = com.openmason.engine.rendering.model.gmr.editable.TestMeshes.lShapedFace();
        List<InsetFacesOp.FaceResult> results = InsetFacesOp.apply(mesh, new int[]{0}, 0.1f);
        assertEquals(1, results.size());
        assertEquals(12, mesh.vertexCount(), "6 outer + 6 inner");
        assertEquals(7, mesh.faceCount(), "cap + 6 border quads");
        MeshInvariants.assertValid(mesh);
    }

    @Test
    void insetRejectsNonPositiveAmount() {
        EditableMesh mesh = cube();
        assertTrue(InsetFacesOp.apply(mesh, new int[]{0}, 0.0f).isEmpty());
        assertTrue(InsetFacesOp.apply(mesh, new int[]{0}, -1.0f).isEmpty());
        assertEquals(6, mesh.faceCount());
    }

    // ── ExtrudeFacesOp ──────────────────────────────────────────────────────

    @Test
    void extrudeSingleCubeFace() {
        EditableMesh mesh = cube();
        List<ExtrudeFacesOp.FaceResult> results = ExtrudeFacesOp.apply(mesh, new int[]{0}, 0.5f);

        assertEquals(1, results.size());
        ExtrudeFacesOp.FaceResult result = results.get(0);
        assertEquals(4, result.sideFaceIds().length);
        assertEquals(12, mesh.vertexCount(), "V+4");
        assertEquals(10, mesh.faceCount(), "F+4");
        MeshInvariants.assertValid(mesh);
        MeshInvariants.assertClosedManifold(mesh);

        // Cap moved by offset along +Z; keeps the original face id.
        EditableFace cap = mesh.face(0);
        for (int i = 0; i < 4; i++) {
            assertEquals(1.0f, mesh.position(cap.vertexAt(i)).z, 1e-5f);
        }
        assertTrue(faceNormal(mesh, 0).z > 0.99f, "cap still faces outward");

        // Side quads face away from the cube axis, not inward.
        for (int sideId : result.sideFaceIds()) {
            Vector3f n = faceNormal(mesh, sideId);
            EditableFace side = mesh.face(sideId);
            Vector3f centroid = new Vector3f();
            for (int i = 0; i < side.loopLength(); i++) {
                centroid.add(mesh.position(side.vertexAt(i)));
            }
            centroid.div(side.loopLength());
            centroid.z = 0; // radial direction from the extrusion axis
            assertTrue(n.dot(centroid) > 0, "side quad " + sideId + " wound outward");
        }
    }

    @Test
    void extrudeMultipleDisjointFaces() {
        EditableMesh mesh = cube();
        List<ExtrudeFacesOp.FaceResult> results =
            ExtrudeFacesOp.apply(mesh, new int[]{0, 1}, 0.25f);
        assertEquals(2, results.size());
        assertEquals(16, mesh.vertexCount());
        assertEquals(14, mesh.faceCount());
        MeshInvariants.assertValid(mesh);
        MeshInvariants.assertClosedManifold(mesh);
    }

    @Test
    void extrudeUnknownFaceIsSkipped() {
        EditableMesh mesh = cube();
        assertTrue(ExtrudeFacesOp.apply(mesh, new int[]{99}, 0.5f).isEmpty());
        assertEquals(8, mesh.vertexCount());
    }
}
