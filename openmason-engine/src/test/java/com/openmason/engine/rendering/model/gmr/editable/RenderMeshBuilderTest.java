package com.openmason.engine.rendering.model.gmr.editable;

import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderMeshBuilderTest {

    @Test
    void cubeDerivesToLegacyLayout() {
        EditableMesh mesh = TestMeshes.cube();
        RenderMesh rm = RenderMeshBuilder.build(mesh, new FaceTextureManager());

        assertEquals(24, rm.cornerCount(), "6 quads x 4 corners — per-face duplication");
        assertEquals(36, rm.indices().length, "12 triangles");
        assertEquals(12, rm.triangleToFaceId().length);
        MeshInvariants.assertRenderMeshConsistent(mesh, rm);
    }

    @Test
    void sharedVertexFansOutToMultipleCorners() {
        EditableMesh mesh = TestMeshes.cube();
        RenderMesh rm = RenderMeshBuilder.build(mesh, new FaceTextureManager());

        // Every cube vertex belongs to exactly 3 faces → 3 corners each.
        for (int v = 0; v < mesh.vertexCount(); v++) {
            assertEquals(3, rm.vertexIdToCorners()[v].length,
                "vertex " + v + " corner fan");
        }
    }

    @Test
    void distinctRegionsProduceDistinctCornerUVsAtSharedVertex() {
        // Two faces sharing an edge, each mapped to a different UV region:
        // the shared vertices must get different UVs per face — the property
        // the legacy seam-duplication machinery existed to provide.
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new org.joml.Vector3f(0, 0, 0));
        int b = mesh.addVertex(new org.joml.Vector3f(1, 0, 0));
        int c = mesh.addVertex(new org.joml.Vector3f(1, 1, 0));
        int d = mesh.addVertex(new org.joml.Vector3f(0, 1, 0));
        int e = mesh.addVertex(new org.joml.Vector3f(2, 0, 0));
        int f = mesh.addVertex(new org.joml.Vector3f(2, 1, 0));
        int left = mesh.addFace(new int[]{a, b, c, d});
        int right = mesh.addFace(new int[]{b, e, f, c});

        // Non-adjacent regions: on the shared boundary vertex the two faces'
        // projected U values must land in disjoint ranges. (Seam-ADJACENT
        // regions are intentionally UV-continuous at the boundary.)
        FaceTextureManager ftm = new FaceTextureManager();
        ftm.setFaceMapping(new FaceTextureMapping(left, 0,
            new FaceTextureMapping.UVRegion(0.0f, 0.0f, 0.4f, 1.0f),
            FaceTextureMapping.UVRotation.NONE));
        ftm.setFaceMapping(new FaceTextureMapping(right, 0,
            new FaceTextureMapping.UVRegion(0.6f, 0.0f, 1.0f, 1.0f),
            FaceTextureMapping.UVRotation.NONE));

        RenderMesh rm = RenderMeshBuilder.build(mesh, ftm);
        MeshInvariants.assertRenderMeshConsistent(mesh, rm);

        // Vertex b has one corner in each face; their U coordinates must differ.
        int[] corners = rm.vertexIdToCorners()[b];
        assertEquals(2, corners.length);
        float u0 = rm.texCoords()[corners[0] * 2];
        float u1 = rm.texCoords()[corners[1] * 2];
        assertNotEquals(u0, u1, 1e-3f, "shared vertex carries per-face UVs");
    }

    @Test
    void uvsLandInsideTheFaceRegion() {
        EditableMesh mesh = TestMeshes.cube();
        FaceTextureManager ftm = new FaceTextureManager();
        FaceTextureMapping.UVRegion region = new FaceTextureMapping.UVRegion(0.25f, 0.25f, 0.75f, 0.75f);
        ftm.setFaceMapping(new FaceTextureMapping(0, 0, region, FaceTextureMapping.UVRotation.NONE));

        RenderMesh rm = RenderMeshBuilder.build(mesh, ftm);
        for (int t = 0; t < rm.triangleCount(); t++) {
            if (rm.triangleToFaceId()[t] != 0) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                int corner = rm.indices()[t * 3 + k];
                float u = rm.texCoords()[corner * 2];
                float v = rm.texCoords()[corner * 2 + 1];
                assertTrue(u >= 0.25f - 1e-5f && u <= 0.75f + 1e-5f, "u in region: " + u);
                assertTrue(v >= 0.25f - 1e-5f && v <= 0.75f + 1e-5f, "v in region: " + v);
            }
        }
    }

    @Test
    void concaveFaceTriangulatesWithoutOverlap() {
        EditableMesh mesh = TestMeshes.lShapedFace();
        RenderMesh rm = RenderMeshBuilder.build(mesh, new FaceTextureManager());

        assertEquals(4, rm.triangleCount(), "hexagon -> 4 triangles");
        MeshInvariants.assertRenderMeshConsistent(mesh, rm);
    }

    @Test
    void nullTextureManagerFallsBackToFullRegion() {
        EditableMesh mesh = TestMeshes.cube();
        RenderMesh rm = RenderMeshBuilder.build(mesh, null);
        for (float uv : rm.texCoords()) {
            assertTrue(uv >= -1e-6f && uv <= 1.0f + 1e-6f);
        }
    }

    @Test
    void emptyMeshDerivesToEmptyRenderMesh() {
        RenderMesh rm = RenderMeshBuilder.build(new EditableMesh(), null);
        assertEquals(0, rm.cornerCount());
        assertEquals(0, rm.triangleCount());
    }

    @Test
    void authoredUVsWinOverProjectionForUnmappedFaces() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new org.joml.Vector3f(0, 0, 0));
        int b = mesh.addVertex(new org.joml.Vector3f(1, 0, 0));
        int c = mesh.addVertex(new org.joml.Vector3f(1, 1, 0));
        int d = mesh.addVertex(new org.joml.Vector3f(0, 1, 0));
        // Authored UVs occupy a sub-rectangle projection would never produce.
        float[] authored = {0.1f, 0.2f, 0.3f, 0.2f, 0.3f, 0.6f, 0.1f, 0.6f};
        mesh.addFace(new int[]{a, b, c, d}, authored);

        RenderMesh rm = RenderMeshBuilder.build(mesh, new FaceTextureManager());
        for (int i = 0; i < 4; i++) {
            assertEquals(authored[i * 2], rm.texCoords()[i * 2], 1e-6f);
            assertEquals(authored[i * 2 + 1], rm.texCoords()[i * 2 + 1], 1e-6f);
        }
    }

    @Test
    void explicitRegionMappingOverridesAuthoredUVs() {
        EditableMesh mesh = new EditableMesh();
        int a = mesh.addVertex(new org.joml.Vector3f(0, 0, 0));
        int b = mesh.addVertex(new org.joml.Vector3f(1, 0, 0));
        int c = mesh.addVertex(new org.joml.Vector3f(1, 1, 0));
        int d = mesh.addVertex(new org.joml.Vector3f(0, 1, 0));
        float[] authored = {0.1f, 0.2f, 0.3f, 0.2f, 0.3f, 0.6f, 0.1f, 0.6f};
        int faceId = mesh.addFace(new int[]{a, b, c, d}, authored);

        FaceTextureManager ftm = new FaceTextureManager();
        FaceTextureMapping.UVRegion region = new FaceTextureMapping.UVRegion(0.5f, 0.5f, 1.0f, 1.0f);
        ftm.setFaceMapping(new FaceTextureMapping(faceId, 0, region, FaceTextureMapping.UVRotation.NONE));

        RenderMesh rm = RenderMeshBuilder.build(mesh, ftm);
        for (int i = 0; i < 4; i++) {
            float u = rm.texCoords()[i * 2];
            float v = rm.texCoords()[i * 2 + 1];
            assertTrue(u >= 0.5f - 1e-5f && v >= 0.5f - 1e-5f,
                "explicit region projection wins: (" + u + ", " + v + ")");
        }
    }
}
