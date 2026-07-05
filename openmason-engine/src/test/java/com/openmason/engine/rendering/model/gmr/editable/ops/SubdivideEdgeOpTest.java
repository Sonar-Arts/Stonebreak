package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshInvariants;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubdivideEdgeOpTest {

    /** Cube built directly (8 shared vertices, 6 quads) to avoid importer coupling. */
    private static EditableMesh cube() {
        EditableMesh m = new EditableMesh();
        float h = 0.5f;
        // Vertex ids 0..7: (x,y,z) sign pattern
        int v000 = m.addVertex(new Vector3f(-h, -h, -h));
        int v100 = m.addVertex(new Vector3f( h, -h, -h));
        int v110 = m.addVertex(new Vector3f( h,  h, -h));
        int v010 = m.addVertex(new Vector3f(-h,  h, -h));
        int v001 = m.addVertex(new Vector3f(-h, -h,  h));
        int v101 = m.addVertex(new Vector3f( h, -h,  h));
        int v111 = m.addVertex(new Vector3f( h,  h,  h));
        int v011 = m.addVertex(new Vector3f(-h,  h,  h));
        m.addFace(new int[]{v001, v101, v111, v011}); // front  (+Z)
        m.addFace(new int[]{v100, v000, v010, v110}); // back   (-Z)
        m.addFace(new int[]{v000, v001, v011, v010}); // left   (-X)
        m.addFace(new int[]{v101, v100, v110, v111}); // right  (+X)
        m.addFace(new int[]{v011, v111, v110, v010}); // top    (+Y)
        m.addFace(new int[]{v000, v100, v101, v001}); // bottom (-Y)
        return m;
    }

    @Test
    void midpointSubdivisionSharedByBothFaces() {
        EditableMesh mesh = cube();
        int before = mesh.vertexCount();

        // Front-bottom edge (v001=4, v101=5) borders front and bottom faces.
        SubdivideEdgeOp.Result result = SubdivideEdgeOp.apply(mesh, 4, 5, 0.5f);

        assertTrue(result != null);
        assertEquals(before + 1, mesh.vertexCount(), "exactly ONE new vertex");
        assertEquals(2, result.affectedFaceIds().length, "both bordering faces spliced");

        for (int faceId : result.affectedFaceIds()) {
            EditableFace face = mesh.face(faceId);
            assertEquals(5, face.loopLength(), "quad became pentagon");
            assertTrue(face.containsVertex(result.newVertexId()));
        }

        Vector3f expected = new Vector3f(0.0f, -0.5f, 0.5f);
        assertTrue(mesh.position(result.newVertexId()).equals(expected, 1e-6f));

        MeshInvariants.assertValid(mesh);
        MeshInvariants.assertClosedManifold(mesh);
        MeshInvariants.assertNoCoincidentLiveVertices(mesh);
    }

    @Test
    void parametricPositionIsExact() {
        EditableMesh mesh = cube();
        SubdivideEdgeOp.Result result = SubdivideEdgeOp.apply(mesh, 4, 5, 0.25f);

        // v4 = (-0.5,-0.5,0.5), v5 = (0.5,-0.5,0.5): t=0.25 → x = -0.25
        Vector3f expected = new Vector3f(-0.25f, -0.5f, 0.5f);
        assertTrue(mesh.position(result.newVertexId()).equals(expected, 1e-6f));
    }

    @Test
    void insertionPreservesLoopDirectionOnBothFaces() {
        EditableMesh mesh = cube();
        SubdivideEdgeOp.Result result = SubdivideEdgeOp.apply(mesh, 4, 5, 0.5f);
        int mid = result.newVertexId();

        // In every affected face, the new vertex must sit directly between
        // v4 and v5 in loop order (either direction).
        for (int faceId : result.affectedFaceIds()) {
            EditableFace face = mesh.face(faceId);
            int n = face.loopLength();
            int mi = face.indexOf(mid);
            int prev = face.vertexAt((mi + n - 1) % n);
            int next = face.vertexAt((mi + 1) % n);
            assertTrue((prev == 4 && next == 5) || (prev == 5 && next == 4),
                "midpoint spliced between original endpoints in face " + faceId);
        }
    }

    @Test
    void chainedSubdivisionsAccumulate() {
        EditableMesh mesh = cube();
        SubdivideEdgeOp.Result first = SubdivideEdgeOp.apply(mesh, 4, 5, 0.5f);
        // Subdivide one of the halves.
        SubdivideEdgeOp.Result second = SubdivideEdgeOp.apply(mesh, 4, first.newVertexId(), 0.5f);

        assertTrue(second != null);
        assertEquals(10, mesh.vertexCount());
        for (int faceId : second.affectedFaceIds()) {
            assertEquals(6, mesh.face(faceId).loopLength(), "pentagon became hexagon");
        }
        MeshInvariants.assertClosedManifold(mesh);
    }

    @Test
    void authoredUVsInterpolateAtTheSplit() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(1, 0, 0));
        int c = mesh.addVertex(new Vector3f(1, 1, 0));
        int d = mesh.addVertex(new Vector3f(0, 1, 0));
        float[] authored = {0.0f, 0.0f, 0.4f, 0.0f, 0.4f, 0.8f, 0.0f, 0.8f};
        int faceId = mesh.addFace(new int[]{a, b, c, d}, authored);

        SubdivideEdgeOp.Result result = SubdivideEdgeOp.apply(mesh, a, b, 0.25f);
        assertTrue(result != null);

        var face = mesh.face(faceId);
        assertTrue(face.hasAuthoredUVs(), "authored UVs survive subdivision");
        float[] uvs = face.cornerUVs();
        int mi = face.indexOf(result.newVertexId());
        // u interpolates 0.0 -> 0.4 at t=0.25 => 0.1; v stays 0.
        assertEquals(0.1f, uvs[mi * 2], 1e-6f);
        assertEquals(0.0f, uvs[mi * 2 + 1], 1e-6f);
    }

    @Test
    void nonEdgeReturnsNull() {
        EditableMesh mesh = cube();
        // v4 (front-bottom-left) and v2 (back-top-right) share no face edge.
        assertNull(SubdivideEdgeOp.apply(mesh, 4, 2, 0.5f));
        assertNull(SubdivideEdgeOp.apply(mesh, 4, 4, 0.5f));
        assertNull(SubdivideEdgeOp.apply(mesh, 4, 99, 0.5f));
        assertEquals(8, mesh.vertexCount(), "failed op must not allocate vertices");
    }
}
