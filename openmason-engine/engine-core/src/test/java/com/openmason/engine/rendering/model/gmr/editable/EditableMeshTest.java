package com.openmason.engine.rendering.model.gmr.editable;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableMeshTest {

    @Test
    void addVertexNeverWelds() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(1, 2, 3));
        int b = mesh.addVertex(new Vector3f(1, 2, 3));
        assertEquals(0, a);
        assertEquals(1, b);
        assertEquals(2, mesh.vertexCount());
    }

    @Test
    void setPositionMovesSharedVertexForAllFaces() {
        EditableMesh mesh = TestMeshes.cube();
        // Vertex 0 is shared by 3 cube faces.
        int sharedFaces = mesh.facesWithVertex(0).size();
        assertEquals(3, sharedFaces);

        mesh.setPosition(0, new Vector3f(9, 9, 9));
        assertEquals(new Vector3f(9, 9, 9), mesh.position(0));
    }

    @Test
    void loopValidationRejectsBadLoops() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new Vector3f(0, 0, 0));
        int b = mesh.addVertex(new Vector3f(1, 0, 0));
        int c = mesh.addVertex(new Vector3f(0, 1, 0));

        assertThrows(IllegalArgumentException.class, () -> mesh.addFace(new int[]{a, b}));
        assertThrows(IllegalArgumentException.class, () -> mesh.addFace(new int[]{a, b, b}));
        assertThrows(IllegalArgumentException.class, () -> mesh.addFace(new int[]{a, b, 99}));
        assertEquals(0, mesh.addFace(new int[]{a, b, c}));
    }

    @Test
    void faceIdsAreStableAndNeverReused() {
        EditableMesh mesh = TestMeshes.cube();
        assertEquals(6, mesh.faceIdUpperBound());
        mesh.removeFace(2);
        assertNull(mesh.face(2));
        assertEquals(5, mesh.faceCount());

        int newId = mesh.addFace(new int[]{0, 1, 2});
        assertEquals(6, newId, "deleted id 2 must not be reused");
        assertEquals(7, mesh.faceIdUpperBound());
    }

    @Test
    void addFaceWithIdPreservesGapsAndBumpsAllocator() {
        EditableMesh mesh = new EditableMesh();
        for (int i = 0; i < 4; i++) {
            mesh.addVertex(new Vector3f(i, 0, 0));
        }
        mesh.addVertex(new Vector3f(0, 1, 0));
        mesh.addFaceWithId(7, new int[]{0, 1, 4});
        assertEquals(8, mesh.faceIdUpperBound());
        assertThrows(IllegalArgumentException.class,
            () -> mesh.addFaceWithId(7, new int[]{1, 2, 4}));
    }

    @Test
    void deepCopyIsIndependent() {
        EditableMesh mesh = TestMeshes.cube();
        EditableMesh copy = mesh.deepCopy();

        assertEquals(mesh.vertexCount(), copy.vertexCount());
        assertEquals(mesh.faceCount(), copy.faceCount());
        assertNotSame(mesh.face(0), copy.face(0));

        mesh.setPosition(0, new Vector3f(5, 5, 5));
        assertFalse(copy.position(0).equals(new Vector3f(5, 5, 5), 1e-6f));

        mesh.removeFace(0);
        assertTrue(copy.face(0) != null);
        MeshInvariants.assertValid(copy);
        MeshInvariants.assertClosedManifold(copy);
    }

    @Test
    void facesWithEdgeFindsBothBorderingFaces() {
        EditableMesh mesh = TestMeshes.cube();
        // Every cube edge borders exactly 2 faces.
        EditableFace front = mesh.face(0);
        int a = front.vertexAt(0);
        int b = front.vertexAt(1);
        assertEquals(2, mesh.facesWithEdge(a, b).size());
    }

    @Test
    void cubeFixtureIsClosedManifold() {
        EditableMesh mesh = TestMeshes.cube();
        assertEquals(8, mesh.vertexCount());
        assertEquals(6, mesh.faceCount());
        MeshInvariants.assertValid(mesh);
        MeshInvariants.assertClosedManifold(mesh);
        MeshInvariants.assertNoCoincidentLiveVertices(mesh);
    }
}
