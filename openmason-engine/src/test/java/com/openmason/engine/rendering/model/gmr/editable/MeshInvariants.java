package com.openmason.engine.rendering.model.gmr.editable;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared structural assertions for {@link EditableMesh} and {@link RenderMesh}.
 * Every mutation-op test runs {@link #assertValid} afterward; closed-mesh
 * fixtures additionally run {@link #assertClosedManifold}.
 */
public final class MeshInvariants {

    private MeshInvariants() {
    }

    /** Loop sanity + edge-sharing bounds for any mesh. */
    public static void assertValid(EditableMesh mesh) {
        Map<Long, Integer> undirectedEdgeUse = new HashMap<>();

        for (EditableFace face : mesh.faces()) {
            int n = face.loopLength();
            assertTrue(n >= 3, "Face " + face.faceId() + " loop too short: " + n);

            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < n; i++) {
                int v = face.vertexAt(i);
                assertTrue(mesh.isValidVertex(v),
                    "Face " + face.faceId() + " references invalid vertex " + v);
                assertTrue(seen.add(v),
                    "Face " + face.faceId() + " repeats vertex " + v);
                undirectedEdgeUse.merge(
                    undirectedKey(v, face.vertexAt((i + 1) % n)), 1, Integer::sum);
            }
        }

        for (Map.Entry<Long, Integer> e : undirectedEdgeUse.entrySet()) {
            assertTrue(e.getValue() <= 2,
                "Edge used by " + e.getValue() + " faces (non-manifold beyond 2)");
        }
    }

    /**
     * No two vertices REFERENCED BY FACES are within welding distance
     * (orphan vertices are exempt — deletes leave them behind by design).
     */
    public static void assertNoCoincidentLiveVertices(EditableMesh mesh) {
        Set<Integer> live = new HashSet<>();
        for (EditableFace face : mesh.faces()) {
            for (int v : face.loop()) {
                live.add(v);
            }
        }
        Integer[] ids = live.toArray(new Integer[0]);
        float epsSq = GMRConstants.POSITION_EPSILON_SQ;
        for (int i = 0; i < ids.length; i++) {
            Vector3f a = mesh.position(ids[i]);
            for (int j = i + 1; j < ids.length; j++) {
                if (a.distanceSquared(mesh.position(ids[j])) < epsSq) {
                    fail("Vertices " + ids[i] + " and " + ids[j] + " are coincident");
                }
            }
        }
    }

    /**
     * Closed-manifold checks: every undirected edge borders exactly 2 faces,
     * used once per direction (consistent winding), and Euler characteristic
     * V − E + F == 2 over face-referenced vertices.
     */
    public static void assertClosedManifold(EditableMesh mesh) {
        Map<Long, Integer> undirected = new HashMap<>();
        Set<Long> directed = new HashSet<>();
        Set<Integer> liveVertices = new HashSet<>();

        for (EditableFace face : mesh.faces()) {
            int n = face.loopLength();
            for (int i = 0; i < n; i++) {
                int a = face.vertexAt(i);
                int b = face.vertexAt((i + 1) % n);
                liveVertices.add(a);
                undirected.merge(undirectedKey(a, b), 1, Integer::sum);
                assertTrue(directed.add(directedKey(a, b)),
                    "Directed edge " + a + "->" + b + " used twice — inconsistent winding");
            }
        }

        for (Map.Entry<Long, Integer> e : undirected.entrySet()) {
            assertEquals(2, e.getValue(),
                "Closed mesh edge must border exactly 2 faces, got " + e.getValue());
        }

        int v = liveVertices.size();
        int eCount = undirected.size();
        int f = mesh.faceCount();
        assertEquals(2, v - eCount + f,
            "Euler characteristic violated: V=" + v + " E=" + eCount + " F=" + f);
    }

    /** Render-mesh consistency against its source mesh. */
    public static void assertRenderMeshConsistent(EditableMesh mesh, RenderMesh rm) {
        int expectedTriangles = 0;
        int expectedCorners = 0;
        for (EditableFace face : mesh.faces()) {
            expectedTriangles += face.loopLength() - 2;
            expectedCorners += face.loopLength();
        }
        assertEquals(expectedCorners, rm.cornerCount(), "corner count");
        assertEquals(expectedTriangles, rm.triangleCount(), "triangle count = sum(loopLen - 2)");

        // Corner map round-trip and position agreement.
        for (int c = 0; c < rm.cornerCount(); c++) {
            int vertexId = rm.cornerToVertexId()[c];
            assertTrue(mesh.isValidVertex(vertexId), "corner " + c + " -> bad vertex");
            Vector3f p = mesh.position(vertexId);
            assertEquals(p.x, rm.vertices()[c * 3], 1e-6f);
            assertEquals(p.y, rm.vertices()[c * 3 + 1], 1e-6f);
            assertEquals(p.z, rm.vertices()[c * 3 + 2], 1e-6f);

            boolean found = false;
            for (int back : rm.vertexIdToCorners()[vertexId]) {
                if (back == c) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "vertexIdToCorners misses corner " + c);
        }

        // Every triangle's face contains the vertices its corners map to.
        for (int t = 0; t < rm.triangleCount(); t++) {
            int faceId = rm.triangleToFaceId()[t];
            EditableFace face = mesh.face(faceId);
            assertTrue(face != null, "triangle " + t + " -> dead face " + faceId);
            for (int k = 0; k < 3; k++) {
                int vertexId = rm.cornerToVertexId()[rm.indices()[t * 3 + k]];
                assertTrue(face.containsVertex(vertexId),
                    "triangle " + t + " corner not in face " + faceId + " loop");
            }
        }
    }

    private static long undirectedKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static long directedKey(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }
}
