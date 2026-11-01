package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshInvariants;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeVerticesOpTest {

    /** Two quads side by side sharing edge (1, 2): 6 vertices, faces 0 and 1. */
    private static EditableMesh twoQuads() {
        EditableMesh mesh = new EditableMesh();
        mesh.addVertex(new Vector3f(0, 0, 0)); // 0
        mesh.addVertex(new Vector3f(1, 0, 0)); // 1
        mesh.addVertex(new Vector3f(1, 1, 0)); // 2
        mesh.addVertex(new Vector3f(0, 1, 0)); // 3
        mesh.addVertex(new Vector3f(2, 0, 0)); // 4
        mesh.addVertex(new Vector3f(2, 1, 0)); // 5
        mesh.addFace(new int[]{0, 1, 2, 3});
        mesh.addFace(new int[]{1, 4, 5, 2});
        return mesh;
    }

    @Test
    void mergingRewritesEveryReferencingFace() {
        EditableMesh mesh = twoQuads();
        // Merge vertex 4 into vertex 1 (as after dragging 4 onto 1).
        mesh.setPosition(4, mesh.position(1));
        MergeVerticesOp.Result result = MergeVerticesOp.apply(mesh, 1, new int[]{4});

        assertTrue(result != null);
        EditableFace right = mesh.face(1);
        assertTrue(right.containsVertex(1), "face rewritten to the kept vertex");
        assertEquals(3, right.loopLength(), "collapsed edge (1,4) removed one corner");
        MeshInvariants.assertValid(mesh);
    }

    @Test
    void faceDegeneratingBelowTriangleIsDropped() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(1, 0, 0));
        int c = mesh.addVertex(new Vector3f(0, 1, 0));
        int faceId = mesh.addFace(new int[]{a, b, c});

        MergeVerticesOp.Result result = MergeVerticesOp.apply(mesh, a, new int[]{b});
        assertTrue(result != null);
        assertEquals(1, result.droppedFaceIds().length);
        assertEquals(faceId, result.droppedFaceIds()[0]);
        assertNull(mesh.face(faceId), "triangle with a collapsed edge dies");
    }

    @Test
    void mergedVertexBecomesOrphanAndStopsAffectingTopology() {
        EditableMesh mesh = twoQuads();
        MergeVerticesOp.apply(mesh, 1, new int[]{4});

        assertTrue(mesh.facesWithVertex(4).isEmpty(), "merged vertex referenced nowhere");
        // Edge (1,5) now exists on the rewritten face; (4,5) does not.
        assertTrue(mesh.facesWithEdge(4, 5).isEmpty());
        assertEquals(1, mesh.facesWithEdge(1, 5).size());
    }

    @Test
    void authoredUVsFollowTheSurvivingCorners() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(1, 0, 0));
        int c = mesh.addVertex(new Vector3f(1, 1, 0));
        int d = mesh.addVertex(new Vector3f(0, 1, 0));
        int e = mesh.addVertex(new Vector3f(2, 0.5f, 0));
        float[] uvs = {0, 0, 0.5f, 0, 0.9f, 0.5f, 0.5f, 1, 0, 1};
        int faceId = mesh.addFace(new int[]{a, b, e, c, d}, uvs);

        // Merge e into b: pentagon becomes a quad, e's corner collapses away.
        MergeVerticesOp.Result result = MergeVerticesOp.apply(mesh, b, new int[]{e});
        assertTrue(result != null);
        EditableFace face = mesh.face(faceId);
        assertEquals(4, face.loopLength());
        assertTrue(face.hasAuthoredUVs());
        float[] survived = face.cornerUVs();
        assertEquals(0.5f, survived[2], 1e-6f, "kept corner keeps its own UV");
        assertEquals(0.0f, survived[3], 1e-6f);
    }

    @Test
    void invalidInputsReturnNull() {
        EditableMesh mesh = twoQuads();
        assertNull(MergeVerticesOp.apply(mesh, 99, new int[]{1}));
        assertNull(MergeVerticesOp.apply(mesh, 1, null));
        assertNull(MergeVerticesOp.apply(mesh, 1, new int[]{1}), "self-merge is a no-op");
        assertNull(MergeVerticesOp.apply(mesh, 1, new int[]{99}));
    }
}
