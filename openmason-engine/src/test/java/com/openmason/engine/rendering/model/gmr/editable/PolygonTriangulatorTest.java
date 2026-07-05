package com.openmason.engine.rendering.model.gmr.editable;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonTriangulatorTest {

    private static final float EPS = 1e-5f;

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Sum of signed triangle areas projected on the loop's Newell normal. */
    private static float triangulatedArea(Vector3f[] loop, int[] tris) {
        Vector3f n = PolygonTriangulator.newellNormal(loop).normalize();
        float area = 0;
        for (int i = 0; i < tris.length; i += 3) {
            Vector3f a = loop[tris[i]];
            Vector3f b = loop[tris[i + 1]];
            Vector3f c = loop[tris[i + 2]];
            Vector3f cross = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            area += 0.5f * cross.dot(n);
        }
        return area;
    }

    private static float polygonArea(Vector3f[] loop) {
        // |Newell normal| / 2 equals the planar polygon area.
        return PolygonTriangulator.newellNormal(loop).length() * 0.5f;
    }

    private static void assertCoversPolygon(Vector3f[] loop) {
        int[] tris = PolygonTriangulator.triangulate(loop);
        assertEquals((loop.length - 2) * 3, tris.length, "triangle count");
        // Signed areas all non-negative (consistent winding, no flipped triangles)
        Vector3f n = PolygonTriangulator.newellNormal(loop).normalize();
        for (int i = 0; i < tris.length; i += 3) {
            Vector3f a = loop[tris[i]];
            Vector3f b = loop[tris[i + 1]];
            Vector3f c = loop[tris[i + 2]];
            float signed = 0.5f * new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).dot(n);
            assertTrue(signed >= -EPS, "flipped triangle at " + i + ": " + signed);
        }
        // Total area matches the polygon (rules out overlap for same-winding triangles)
        assertEquals(polygonArea(loop), triangulatedArea(loop, tris), 1e-4f, "area coverage");
    }

    // ── Cases ───────────────────────────────────────────────────────────────

    @Test
    void trianglePassesThrough() {
        Vector3f[] tri = {
            new Vector3f(0, 0, 0), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0)
        };
        assertArrayEqualsInt(new int[]{0, 1, 2}, PolygonTriangulator.triangulate(tri));
    }

    @Test
    void squareIsTwoTriangles() {
        Vector3f[] square = {
            new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
            new Vector3f(1, 1, 0), new Vector3f(0, 1, 0)
        };
        assertCoversPolygon(square);
    }

    @Test
    void concaveLShape() {
        assertCoversPolygon(TestMeshes.lShapeLoop());
    }

    @Test
    void concaveArrow() {
        // Arrowhead: deep reflex notch at the back.
        Vector3f[] arrow = {
            new Vector3f(0, 0, 0),
            new Vector3f(2, 1, 0),
            new Vector3f(0, 2, 0),
            new Vector3f(0.8f, 1, 0), // reflex
        };
        assertCoversPolygon(arrow);
    }

    @Test
    void collinearVertexOnEdge() {
        // Square with a subdivision midpoint on the bottom edge: [A, mid, B, C, D].
        Vector3f[] loop = {
            new Vector3f(0, 0, 0),
            new Vector3f(0.5f, 0, 0), // collinear midpoint
            new Vector3f(1, 0, 0),
            new Vector3f(1, 1, 0),
            new Vector3f(0, 1, 0)
        };
        assertCoversPolygon(loop);
    }

    @Test
    void nonPlanarQuadStillProducesTwoTriangles() {
        // One corner lifted out of plane — best-fit projection must still work.
        Vector3f[] quad = {
            new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
            new Vector3f(1, 1, 0.3f), new Vector3f(0, 1, 0)
        };
        int[] tris = PolygonTriangulator.triangulate(quad);
        assertEquals(6, tris.length);
    }

    @Test
    void sliverPolygonDoesNotCrash() {
        Vector3f[] sliver = {
            new Vector3f(0, 0, 0),
            new Vector3f(1, 1e-8f, 0),
            new Vector3f(2, 0, 0),
            new Vector3f(1, 1e-8f, 0.0000001f)
        };
        int[] tris = PolygonTriangulator.triangulate(sliver);
        assertEquals(6, tris.length, "degenerate input still emits n-2 triangles");
    }

    @Test
    void clockwiseWindingHandled() {
        // Same square, opposite (CW around +Z, i.e. CCW around -Z) winding.
        Vector3f[] square = {
            new Vector3f(0, 1, 0), new Vector3f(1, 1, 0),
            new Vector3f(1, 0, 0), new Vector3f(0, 0, 0)
        };
        assertCoversPolygon(square);
    }

    @Test
    void concaveVerticalFace() {
        // L-shape standing in the YZ plane (normal along X) — exercises the
        // tangent-frame path for non-axis-favored orientations.
        Vector3f[] loop = {
            new Vector3f(0, 0, 0),
            new Vector3f(0, 2, 0),
            new Vector3f(0, 2, 1),
            new Vector3f(0, 1, 1),
            new Vector3f(0, 1, 2),
            new Vector3f(0, 0, 2),
        };
        assertCoversPolygon(loop);
    }

    @Test
    void tooFewVerticesThrows() {
        Vector3f[] two = {new Vector3f(), new Vector3f(1, 0, 0)};
        assertThrows(IllegalArgumentException.class, () -> PolygonTriangulator.triangulate(two));
    }

    @Test
    void newellNormalOfCcwXyLoopPointsPlusZ() {
        Vector3f n = PolygonTriangulator.newellNormal(TestMeshes.lShapeLoop()).normalize();
        assertEquals(0, n.x, EPS);
        assertEquals(0, n.y, EPS);
        assertEquals(1, n.z, EPS);
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }
}
