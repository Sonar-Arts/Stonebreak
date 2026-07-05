package com.openmason.engine.rendering.model.gmr.editable;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshImporterTest {

    @Test
    void cubeSoupBecomesEightVerticesSixQuads() {
        EditableMesh mesh = TestMeshes.cube();

        assertEquals(8, mesh.vertexCount(), "24 duplicated corners weld to 8 shared vertices");
        assertEquals(6, mesh.faceCount());
        for (EditableFace face : mesh.faces()) {
            assertEquals(4, face.loopLength(), "cube faces are quads");
        }
        MeshInvariants.assertValid(mesh);
        MeshInvariants.assertClosedManifold(mesh);
    }

    @Test
    void faceIdsPreservedVerbatimIncludingGaps() {
        // Same cube soup but with face ids 0,1,2,3,4,7 (gap at 5,6 — as after deletes).
        int[] triToFace = TestMeshes.cubeTriangleToFaceId();
        for (int t = 0; t < triToFace.length; t++) {
            if (triToFace[t] == 5) {
                triToFace[t] = 7;
            }
        }
        EditableMesh mesh = MeshImporter.importSoup(
            TestMeshes.cubeSoupVertices(), TestMeshes.cubeSoupIndices(), triToFace);

        assertNotNull(mesh.face(7));
        assertNull(mesh.face(5));
        assertNull(mesh.face(6));
        assertEquals(8, mesh.faceIdUpperBound());
        MeshInvariants.assertClosedManifold(mesh);
    }

    @Test
    void windingIsReconstructedCcw() {
        EditableMesh mesh = TestMeshes.cube();
        // Every face's Newell normal must point away from the cube center —
        // i.e. reconstruction preserved the soup's CCW-from-outside winding.
        for (EditableFace face : mesh.faces()) {
            int n = face.loopLength();
            Vector3f[] loop = new Vector3f[n];
            Vector3f centroid = new Vector3f();
            for (int i = 0; i < n; i++) {
                loop[i] = mesh.position(face.vertexAt(i));
                centroid.add(loop[i]);
            }
            centroid.div(n);
            Vector3f normal = PolygonTriangulator.newellNormal(loop);
            assertTrue(normal.dot(centroid) > 0,
                "face " + face.faceId() + " normal points inward");
        }
    }

    @Test
    void coincidentDuplicateMidpointsWeldIntoOneVertex() {
        // Two triangles of one face sharing an edge, where the shared edge
        // endpoints are DUPLICATED soup vertices (the legacy subdivision
        // pattern): import must weld them and reconstruct a single quad.
        float[] vertices = {
            0, 0, 0,   1, 0, 0,   1, 1, 0,   // tri A
            0, 0, 0,   1, 1, 0,   0, 1, 0,   // tri B (corners 3,4 coincide with 0,2)
        };
        int[] indices = {0, 1, 2, 3, 4, 5};
        int[] triToFace = {0, 0};

        EditableMesh mesh = MeshImporter.importSoup(vertices, indices, triToFace);
        assertEquals(4, mesh.vertexCount());
        assertEquals(1, mesh.faceCount());
        assertEquals(4, mesh.face(0).loopLength(), "reconstructed as one quad");
        MeshInvariants.assertValid(mesh);
    }

    @Test
    void degenerateTrianglesAreDropped() {
        float[] vertices = {
            0, 0, 0,   1, 0, 0,   1, 1, 0,
            0, 0, 0,   0.00001f, 0, 0,   1, 0, 0,  // collapses after welding
        };
        int[] indices = {0, 1, 2, 3, 4, 5};
        int[] triToFace = {0, 1};

        EditableMesh mesh = MeshImporter.importSoup(vertices, indices, triToFace);
        assertEquals(1, mesh.faceCount(), "welded-degenerate face dropped");
        assertNotNull(mesh.face(0));
    }

    @Test
    void emptyInputYieldsEmptyMesh() {
        EditableMesh mesh = MeshImporter.importSoup(null, null, null);
        assertEquals(0, mesh.vertexCount());
        assertEquals(0, mesh.faceCount());
    }

    @Test
    void authoredUVsAttachPerFaceCorner() {
        // One quad whose soup carries authored (non-projectable) UVs.
        float[] vertices = {
            0, 0, 0,   1, 0, 0,   1, 1, 0,   0, 1, 0,
        };
        float[] texCoords = {
            0.1f, 0.2f,   0.3f, 0.2f,   0.3f, 0.6f,   0.1f, 0.6f,
        };
        int[] indices = {0, 1, 2, 2, 3, 0};
        int[] triToFace = {0, 0};

        MeshImporter.ImportResult result =
            MeshImporter.importSoup(vertices, texCoords, indices, triToFace);
        EditableFace face = result.mesh().face(0);

        assertTrue(face.hasAuthoredUVs());
        float[] uvs = face.cornerUVs();
        // Loop order starts at the first triangle's first corner (soup 0).
        for (int i = 0; i < 4; i++) {
            int v = face.vertexAt(i);
            assertEquals(texCoords[v * 2], uvs[i * 2], 1e-6f, "u for loop pos " + i);
            assertEquals(texCoords[v * 2 + 1], uvs[i * 2 + 1], 1e-6f);
        }
    }

    @Test
    void soupIndexMappingCoversWeldedVertices() {
        MeshImporter.ImportResult result = MeshImporter.importSoup(
            TestMeshes.cubeSoupVertices(), null,
            TestMeshes.cubeSoupIndices(), TestMeshes.cubeTriangleToFaceId());

        int[][] map = result.vertexIdToSoupIndices();
        assertEquals(8, map.length);
        for (int v = 0; v < 8; v++) {
            assertEquals(3, map[v].length, "cube vertex welds 3 soup corners");
            for (int soupIdx : map[v]) {
                assertTrue(soupIdx >= 0 && soupIdx < 24, "vertex " + v + " -> soup " + soupIdx);
            }
        }
    }
}
