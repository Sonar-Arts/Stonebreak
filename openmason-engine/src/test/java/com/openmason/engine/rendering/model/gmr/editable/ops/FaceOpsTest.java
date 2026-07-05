package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshInvariants;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link SplitFaceOp}, {@link CreateFaceOp} and {@link DeleteFaceOp}. */
class FaceOpsTest {

    /** One unit quad in the XY plane: vertices 0..3 CCW, face id 0. */
    private static EditableMesh quad() {
        EditableMesh mesh = new EditableMesh();
        mesh.addVertex(new Vector3f(0, 0, 0));
        mesh.addVertex(new Vector3f(1, 0, 0));
        mesh.addVertex(new Vector3f(1, 1, 0));
        mesh.addVertex(new Vector3f(0, 1, 0));
        mesh.addFace(new int[]{0, 1, 2, 3});
        return mesh;
    }

    // ── SplitFaceOp ─────────────────────────────────────────────────────────

    @Test
    void splitQuadAcrossDiagonal() {
        EditableMesh mesh = quad();
        List<SplitFaceOp.Split> splits = SplitFaceOp.apply(mesh, 0, 2);

        assertEquals(1, splits.size());
        SplitFaceOp.Split split = splits.get(0);
        assertEquals(0, split.parentFaceId());
        assertEquals(1, split.newFaceId());

        EditableFace parent = mesh.face(0);
        EditableFace child = mesh.face(1);
        assertEquals(3, parent.loopLength());
        assertEquals(3, child.loopLength());

        // Parent half runs vA -> ... -> vB in original order: {0, 1, 2}.
        assertTrue(parent.containsVertex(0) && parent.containsVertex(1) && parent.containsVertex(2));
        // Child half: {2, 3, 0}.
        assertTrue(child.containsVertex(2) && child.containsVertex(3) && child.containsVertex(0));

        assertEquals(4, mesh.vertexCount(), "split creates no vertices");
        MeshInvariants.assertValid(mesh);
    }

    @Test
    void splitHexagonKeepsWinding() {
        // Hexagon 0..5; split 1-4 gives two quads sharing edge (1,4).
        EditableMesh mesh = new EditableMesh();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3 * i;
            mesh.addVertex(new Vector3f((float) Math.cos(a), (float) Math.sin(a), 0));
        }
        mesh.addFace(new int[]{0, 1, 2, 3, 4, 5});

        List<SplitFaceOp.Split> splits = SplitFaceOp.apply(mesh, 1, 4);
        assertEquals(1, splits.size());

        EditableFace parent = mesh.face(0);
        EditableFace child = mesh.face(splits.get(0).newFaceId());
        assertEquals(4, parent.loopLength());
        assertEquals(4, child.loopLength());

        // Shared edge (1,4) traversed once per direction — consistent winding.
        int p = parent.adjacentPairIndex(1, 4);
        int c = child.adjacentPairIndex(1, 4);
        assertTrue(p >= 0 && c >= 0);
        assertTrue(parent.vertexAt(p) != child.vertexAt(c),
            "the two halves traverse the shared edge in opposite directions");
        MeshInvariants.assertValid(mesh);
    }

    @Test
    void adjacentVerticesDoNotSplit() {
        EditableMesh mesh = quad();
        assertTrue(SplitFaceOp.apply(mesh, 0, 1).isEmpty(), "existing edge — nothing to split");
        assertTrue(SplitFaceOp.apply(mesh, 0, 0).isEmpty());
        assertEquals(1, mesh.faceCount());
    }

    // ── CreateFaceOp ────────────────────────────────────────────────────────

    @Test
    void createFaceUsesGivenOrderAsWinding() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(1, 0, 0));
        int c = mesh.addVertex(new Vector3f(1, 1, 0));

        int faceId = CreateFaceOp.apply(mesh, new int[]{a, b, c});
        assertEquals(0, faceId);
        EditableFace face = mesh.face(faceId);
        assertEquals(a, face.vertexAt(0));
        assertEquals(b, face.vertexAt(1));
        assertEquals(c, face.vertexAt(2));
    }

    @Test
    void createFaceRejectsBadInput() {
        EditableMesh mesh = quad();
        assertEquals(-1, CreateFaceOp.apply(mesh, null));
        assertEquals(-1, CreateFaceOp.apply(mesh, new int[]{0, 1}));
        assertEquals(-1, CreateFaceOp.apply(mesh, new int[]{0, 1, 99}));
        assertEquals(-1, CreateFaceOp.apply(mesh, new int[]{0, 1, 1}));
        assertEquals(1, mesh.faceCount(), "failed creates leave the mesh untouched");
    }

    // ── DeleteFaceOp ────────────────────────────────────────────────────────

    @Test
    void deleteLeavesVerticesAndGap() {
        EditableMesh mesh = quad();
        assertTrue(DeleteFaceOp.apply(mesh, 0));
        assertNull(mesh.face(0));
        assertEquals(0, mesh.faceCount());
        assertEquals(4, mesh.vertexCount(), "vertices intentionally survive");
        assertEquals(1, mesh.faceIdUpperBound(), "id gap preserved");

        assertFalse(DeleteFaceOp.apply(mesh, 0), "double delete is a no-op");
    }

    @Test
    void deleteThenRefillRoundTrip() {
        EditableMesh mesh = quad();
        int[] loop = mesh.face(0).loop();
        DeleteFaceOp.apply(mesh, 0);

        int refilled = CreateFaceOp.apply(mesh, loop);
        assertEquals(1, refilled, "new id — deleted ids are never reused");
        assertNotNull(mesh.face(1));
        assertEquals(4, mesh.face(1).loopLength());
        MeshInvariants.assertValid(mesh);
    }
}
