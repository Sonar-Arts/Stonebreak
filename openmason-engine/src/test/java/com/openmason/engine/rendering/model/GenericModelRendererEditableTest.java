package com.openmason.engine.rendering.model;

import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression tests for the EditableMesh-backed GMR internals.
 * The golden values (24 corners / 36 indices / 8 unique vertices for a cube)
 * match the legacy triangle-soup pipeline, so these tests pin the public-API
 * compatibility contract across the swap.
 */
class GenericModelRendererEditableTest {

    /** GL-free GMR: marks itself initialized and swallows buffer uploads. */
    private static class HeadlessGmr extends GenericModelRenderer {
        HeadlessGmr() {
            initialized = true;
        }

        @Override
        protected void updateVBO(float[] vertices) {
            // headless — no GL
        }

        @Override
        protected void updateEBO(int[] indices) {
            // headless — no GL
        }
    }

    /** CubeShape-layout unit-cube soup (24 duplicated corners, 12 triangles). */
    private static OMOFormat.MeshData cubeMeshData() {
        float h = 0.5f;
        float[] vertices = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,   // front  (+Z)
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,   // back   (-Z)
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,   // left   (-X)
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,   // right  (+X)
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,   // top    (+Y)
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,   // bottom (-Y)
        };
        float[] texCoords = new float[24 * 2];
        int[] indices = new int[36];
        int idx = 0;
        for (int face = 0; face < 6; face++) {
            int base = face * 4;
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;
            indices[idx++] = base + 2;
            indices[idx++] = base + 3;
            indices[idx++] = base;
        }
        int[] triToFace = new int[12];
        for (int i = 0; i < 12; i++) {
            triToFace[i] = i / 2;
        }
        return new OMOFormat.MeshData(vertices, texCoords, indices, triToFace, "FLAT");
    }

    private HeadlessGmr gmr;

    @BeforeEach
    void setUp() {
        gmr = new HeadlessGmr();
        gmr.loadMeshData(cubeMeshData());
    }

    // ── Load goldens (legacy-pipeline values) ───────────────────────────────

    @Test
    void cubeLoadMatchesLegacyGoldens() {
        assertEquals(24, gmr.getTotalVertexCount(), "24 render corners");
        assertEquals(12, gmr.getTriangleCount());
        assertEquals(6, gmr.getOriginalFaceCount());
        assertEquals(8, gmr.getUniqueVertexCount(), "8 shared vertices");
        assertNotNull(gmr.getTopology());
        assertEquals(12, gmr.getTopology().getEdgeCount(), "cube has 12 edges");
    }

    @Test
    void uniqueAndMeshIndexContractsHold() {
        for (int corner = 0; corner < 24; corner++) {
            int unique = gmr.getUniqueIndexForMeshVertex(corner);
            assertTrue(unique >= 0 && unique < 8, "corner " + corner + " -> " + unique);

            boolean found = false;
            for (int back : gmr.getMeshIndicesForUniqueVertex(unique)) {
                if (back == corner) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "round trip for corner " + corner);
        }
        // Every cube vertex is shared by exactly 3 faces → 3 corners.
        for (int u = 0; u < 8; u++) {
            assertEquals(3, gmr.getMeshIndicesForUniqueVertex(u).length);
        }
    }

    // ── Vertex move ─────────────────────────────────────────────────────────

    @Test
    void movingOneCornerMovesAllCoincidentCorners() {
        int unique = gmr.getUniqueIndexForMeshVertex(0);
        int[] corners = gmr.getMeshIndicesForUniqueVertex(unique);
        assertEquals(3, corners.length);

        Vector3f target = new Vector3f(2, 3, 4);
        gmr.updateVertexPosition(0, target);

        for (int corner : corners) {
            assertTrue(gmr.getVertexPosition(corner).equals(target, 1e-6f),
                "corner " + corner + " moved with its shared vertex");
        }
        assertTrue(gmr.getUniqueVertexPosition(unique).equals(target, 1e-6f));
    }

    // ── Subdivision ─────────────────────────────────────────────────────────

    @Test
    void subdivisionAddsExactlyOneSharedVertex() {
        // Find an edge from topology to subdivide.
        var edge = gmr.getTopology().getEdge(0);
        int newUnique = gmr.subdivideEdgeAtParameter(edge.vertexA(), edge.vertexB(), 0.5f);

        assertTrue(newUnique >= 0);
        assertEquals(9, gmr.getUniqueVertexCount(), "ONE new shared vertex, no duplicates");
        assertEquals(6, gmr.getOriginalFaceCount(), "face count unchanged");
        assertEquals(14, gmr.getTriangleCount(), "two quads became pentagons: 2x3 + 4x2");

        Vector3f expected = gmr.getUniqueVertexPosition(edge.vertexA())
            .lerp(gmr.getUniqueVertexPosition(edge.vertexB()), 0.5f);
        assertTrue(gmr.getUniqueVertexPosition(newUnique).equals(expected, 1e-5f));
    }

    @Test
    void subdivisionByPositionResolvesEndpoints() {
        var edge = gmr.getTopology().getEdge(0);
        Vector3f a = gmr.getUniqueVertexPosition(edge.vertexA());
        Vector3f b = gmr.getUniqueVertexPosition(edge.vertexB());
        Vector3f mid = new Vector3f(a).lerp(b, 0.5f);

        int meshVertex = gmr.applyEdgeSubdivisionByPosition(mid, a, b);
        assertTrue(meshVertex >= 0, "position-based subdivision resolves ids");
        assertEquals(9, gmr.getUniqueVertexCount());
    }

    // ── Face delete / create round trip ─────────────────────────────────────

    @Test
    void deleteThenFillFaceRoundTrip() {
        int[] loop = gmr.getTopology().getFace(0).vertexIndices().clone();

        assertTrue(gmr.deleteFace(0));
        assertEquals(5, gmr.getOriginalFaceCount());
        assertEquals(20, gmr.getTotalVertexCount(), "face corners gone from render mesh");
        assertEquals(8, gmr.getUniqueVertexCount(), "shared vertices survive deletion");

        assertTrue(gmr.createFaceFromVertices(loop));
        assertEquals(6, gmr.getOriginalFaceCount());
        assertEquals(24, gmr.getTotalVertexCount());
        assertFalse(gmr.deleteFace(0), "old face id is gone for good");
    }

    // ── Edge insertion (face split) ─────────────────────────────────────────

    @Test
    void insertEdgeSplitsQuadIntoTwoTriangles() {
        var face = gmr.getTopology().getFace(0);
        int[] loop = face.vertexIndices();
        // Diagonal: loop[0] to loop[2] are non-adjacent in a quad.
        assertTrue(gmr.insertEdgeBetweenVertices(loop[0], loop[2]));
        assertEquals(7, gmr.getOriginalFaceCount(), "one quad became two faces");
        assertEquals(8, gmr.getUniqueVertexCount(), "no vertices created");
    }

    // ── Vertex merge (drag-vertex-onto-vertex commit) ───────────────────────

    @Test
    void mergeVerticesCollapsesEdgeTopologically() {
        var edge = gmr.getTopology().getEdge(0);
        int keep = edge.vertexA();
        int merged = edge.vertexB();

        // Drag semantics: move the merged vertex onto the kept one first.
        Vector3f target = gmr.getUniqueVertexPosition(keep);
        gmr.updateVertexPosition(gmr.getMeshIndicesForUniqueVertex(merged)[0], target);

        assertTrue(gmr.mergeVertices(keep, new int[]{merged}));

        // The two quads bordering edge (keep, merged) each lose a corner and
        // become triangles; the third face referencing the merged vertex is
        // rewritten to the kept vertex.
        assertEquals(6, gmr.getOriginalFaceCount(), "no face degenerates on a cube edge merge");
        assertEquals(22, gmr.getTotalVertexCount(), "two corners collapsed");
        assertEquals(10, gmr.getTriangleCount(), "two quads became triangles");

        // The merged vertex is an orphan: never referenced by any face again.
        assertEquals(0, gmr.getMeshIndicesForUniqueVertex(merged).length);
        assertTrue(gmr.getMeshIndicesForUniqueVertex(keep).length >= 3,
            "faces genuinely share the kept vertex");
    }

    @Test
    void mergeVerticesDropsDegeneratedFaces() {
        // Split a quad into two triangles, then collapse one triangle's edge —
        // that triangle degenerates below 3 distinct vertices and is removed.
        int[] loop = gmr.getTopology().getFace(0).vertexIndices();
        assertTrue(gmr.insertEdgeBetweenVertices(loop[0], loop[2]));
        assertEquals(7, gmr.getOriginalFaceCount());

        assertTrue(gmr.mergeVertices(loop[0], new int[]{loop[1]}));
        assertEquals(6, gmr.getOriginalFaceCount(), "degenerated triangle dropped");
    }

    @Test
    void subdivideThenMergeRoundTripThroughSnapshot() {
        var before = gmr.captureSnapshot();

        // Subdivide an edge, then merge the new midpoint back into an endpoint
        // (the exact drag-merge commit flow: move together, then merge).
        var edge = gmr.getTopology().getEdge(0);
        int newVertex = gmr.subdivideEdgeAtParameter(edge.vertexA(), edge.vertexB(), 0.5f);
        assertTrue(newVertex >= 0);
        assertEquals(14, gmr.getTriangleCount());

        Vector3f target = gmr.getUniqueVertexPosition(edge.vertexA());
        gmr.updateVertexPosition(gmr.getMeshIndicesForUniqueVertex(newVertex)[0], target);
        assertTrue(gmr.mergeVertices(edge.vertexA(), new int[]{newVertex}));

        // Merging the midpoint away restores the cube's render topology.
        assertEquals(6, gmr.getOriginalFaceCount());
        assertEquals(24, gmr.getTotalVertexCount());
        assertEquals(12, gmr.getTriangleCount());

        // And the snapshot undoes the whole thing exactly.
        gmr.restoreSnapshot(before);
        assertEquals(8, gmr.getUniqueVertexCount());
        assertEquals(24, gmr.getTotalVertexCount());
        assertEquals(12, gmr.getTriangleCount());
    }

    @Test
    void mergeVerticesRejectsInvalidInput() {
        assertFalse(gmr.mergeVertices(0, new int[0]), "empty merge set is a no-op");
        assertFalse(gmr.mergeVertices(0, new int[]{0}), "kept id alone is a no-op");
        assertFalse(gmr.mergeVertices(999, new int[]{1}), "invalid kept vertex fails cleanly");
    }

    // ── Serialization round trip ────────────────────────────────────────────

    @Test
    void omoRoundTripIsIdempotent() {
        OMOFormat.MeshData first = gmr.toMeshData();
        assertNotNull(first);

        // Reload the exported mesh several times — counts must stay fixed
        // (the legacy pipeline grew vertices under seam duplication).
        for (int cycle = 0; cycle < 3; cycle++) {
            gmr.loadMeshData(gmr.toMeshData());
        }
        assertEquals(first.getVertexCount(), gmr.toMeshData().getVertexCount());
        assertEquals(24, gmr.getTotalVertexCount());
        assertEquals(8, gmr.getUniqueVertexCount());
        assertEquals(6, gmr.getOriginalFaceCount());
    }

    @Test
    void roundTripAfterSubdivisionPreservesTopology() {
        var edge = gmr.getTopology().getEdge(0);
        gmr.subdivideEdgeAtParameter(edge.vertexA(), edge.vertexB(), 0.25f);

        OMOFormat.MeshData exported = gmr.toMeshData();
        HeadlessGmr fresh = new HeadlessGmr();
        fresh.loadMeshData(exported);

        assertEquals(9, fresh.getUniqueVertexCount());
        assertEquals(6, fresh.getOriginalFaceCount());
        assertEquals(gmr.getTriangleCount(), fresh.getTriangleCount());
    }

    // ── Snapshot restore ────────────────────────────────────────────────────

    @Test
    void snapshotV2RestoreIsExact() {
        var before = gmr.captureSnapshot();

        var edge = gmr.getTopology().getEdge(0);
        gmr.subdivideEdgeAtParameter(edge.vertexA(), edge.vertexB(), 0.5f);
        gmr.deleteFace(3);
        assertEquals(9, gmr.getUniqueVertexCount());

        gmr.restoreSnapshot(before);
        assertEquals(8, gmr.getUniqueVertexCount());
        assertEquals(6, gmr.getOriginalFaceCount());
        assertEquals(24, gmr.getTotalVertexCount());
    }

    @Test
    void snapshotV2SurvivesCoincidentVertices() {
        // Drag vertex 1 onto vertex 0's position, snapshot, drag it away,
        // then restore: both vertices must remain DISTINCT (the legacy
        // array-based restore re-welded them into one).
        Vector3f p0 = gmr.getUniqueVertexPosition(0);
        int corner1 = gmr.getMeshIndicesForUniqueVertex(1)[0];
        gmr.updateVertexPosition(corner1, p0);

        var snapshot = gmr.captureSnapshot();
        gmr.updateVertexPosition(corner1, new Vector3f(9, 9, 9));

        gmr.restoreSnapshot(snapshot);
        assertEquals(8, gmr.getUniqueVertexCount(), "coincident vertices stay distinct");
        assertTrue(gmr.getUniqueVertexPosition(1).equals(p0, 1e-6f));
        assertEquals(12, gmr.getTriangleCount());
    }

    @Test
    void snapshotV2RestoresAcrossShapingOps() {
        var before = gmr.captureSnapshot();

        gmr.extrudeFaces(new int[]{0}, 0.5f);
        gmr.insetFaces(new int[]{1}, 0.1f);
        gmr.scaleFaces(new int[]{2}, 2.0f, null);
        assertTrue(gmr.getOriginalFaceCount() > 6);

        gmr.restoreSnapshot(before);
        assertEquals(6, gmr.getOriginalFaceCount());
        assertEquals(8, gmr.getUniqueVertexCount());
        assertEquals(24, gmr.getTotalVertexCount());
    }

    @Test
    void snapshotContentEqualsDetectsNoOpsAndChanges() {
        var a = gmr.captureSnapshot();
        var b = gmr.captureSnapshot();
        assertTrue(a.contentEquals(b), "back-to-back captures are content-equal");

        gmr.updateVertexPosition(0, new Vector3f(5, 5, 5));
        assertFalse(a.contentEquals(gmr.captureSnapshot()), "a moved vertex is detected");

        gmr.restoreSnapshot(a);
        assertTrue(a.contentEquals(gmr.captureSnapshot()), "restore round-trips to equal content");
    }

    // ── New shaping ops through the public API ──────────────────────────────

    @Test
    void shapingOpsWorkThroughGmr() {
        int[] sides = gmr.extrudeFaces(new int[]{0}, 0.5f);
        assertNotNull(sides);
        assertEquals(4, sides.length);
        assertEquals(10, gmr.getOriginalFaceCount());

        int[] borders = gmr.insetFaces(new int[]{1}, 0.1f);
        assertNotNull(borders);
        assertEquals(4, borders.length);
        assertEquals(14, gmr.getOriginalFaceCount());

        assertTrue(gmr.scaleFaces(new int[]{2}, 0.5f, null));
        assertNull(gmr.extrudeFaces(new int[]{999}, 0.5f), "unknown face fails cleanly");
    }

    @Test
    @SuppressWarnings("deprecation") // pins the legacy array-restore path until it is removed
    void snapshotRestoreUndoesAMutation() {
        float[] vertices = gmr.getAllMeshVertexPositions().clone();
        float[] texCoords = gmr.getTexCoords();
        int[] indices = gmr.getIndices();
        int[] faceMap = gmr.getTriangleToFaceMapping();

        var edge = gmr.getTopology().getEdge(0);
        gmr.subdivideEdgeAtParameter(edge.vertexA(), edge.vertexB(), 0.5f);
        assertEquals(9, gmr.getUniqueVertexCount());

        gmr.restoreFromSnapshot(vertices, texCoords, indices, faceMap,
            new LinkedHashMap<>(), new LinkedHashMap<>());

        assertEquals(8, gmr.getUniqueVertexCount());
        assertEquals(24, gmr.getTotalVertexCount());
        assertEquals(12, gmr.getTriangleCount());
        assertEquals(6, gmr.getOriginalFaceCount());
    }
}
