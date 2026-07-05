package com.openmason.main.systems.viewport.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With an identity MVP and a 100×100 viewport, model (x, y) maps to screen
 * ((x + 1) * 50, (1 − y) * 50) — so model (0, 0) is screen (50, 50).
 */
class BoxSelectMathTest {

    private static final float EPS = 1e-4f;
    private static final int VIEWPORT = 100;
    private static final Matrix4f IDENTITY = new Matrix4f();

    // ── Rect normalization ────────────────────────────────────────────────

    @Test
    void normalizeRectHandlesAllFourDragDirections() {
        float[] expected = {10f, 20f, 30f, 40f};

        // Down-right, down-left, up-right, up-left
        assertArrayEquals(expected, BoxSelectMath.normalizeRect(10, 20, 30, 40), EPS);
        assertArrayEquals(expected, BoxSelectMath.normalizeRect(30, 20, 10, 40), EPS);
        assertArrayEquals(expected, BoxSelectMath.normalizeRect(10, 40, 30, 20), EPS);
        assertArrayEquals(expected, BoxSelectMath.normalizeRect(30, 40, 10, 20), EPS);
    }

    @Test
    void containsIsInclusiveOnBounds() {
        float[] rect = {10f, 20f, 30f, 40f};

        assertTrue(BoxSelectMath.contains(rect, 20, 30));
        assertTrue(BoxSelectMath.contains(rect, 10, 20)); // Min corner
        assertTrue(BoxSelectMath.contains(rect, 30, 40)); // Max corner
        assertFalse(BoxSelectMath.contains(rect, 9.9f, 30));
        assertFalse(BoxSelectMath.contains(rect, 20, 40.1f));
    }

    // ── Vertices ──────────────────────────────────────────────────────────

    @Test
    void verticesInRectSelectsOnlyProjectedHits() {
        // Screen positions: v0 (50,50), v1 (90,10), v2 (5,50)
        float[] positions = {
            0.0f, 0.0f, 0.0f,
            0.8f, 0.8f, 0.0f,
            -0.9f, 0.0f, 0.0f
        };

        float[] rect = BoxSelectMath.normalizeRect(40, 40, 60, 60);
        int[] hits = BoxSelectMath.verticesInRect(positions, 3, IDENTITY, VIEWPORT, VIEWPORT, rect);
        assertArrayEquals(new int[]{0}, hits);

        // Wide rect catches all three
        float[] all = BoxSelectMath.normalizeRect(0, 0, 100, 100);
        assertArrayEquals(new int[]{0, 1, 2},
            BoxSelectMath.verticesInRect(positions, 3, IDENTITY, VIEWPORT, VIEWPORT, all));
    }

    @Test
    void verticesBehindCameraAreExcluded() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.1f, 100f);

        // One vertex in front of the camera, one behind
        float[] positions = {
            0.0f, 0.0f, -5.0f,
            0.0f, 0.0f, 5.0f
        };

        float[] hugeRect = BoxSelectMath.normalizeRect(-1000, -1000, 1000, 1000);
        int[] hits = BoxSelectMath.verticesInRect(positions, 2, perspective, VIEWPORT, VIEWPORT, hugeRect);
        assertArrayEquals(new int[]{0}, hits);
    }

    // ── Edges ─────────────────────────────────────────────────────────────

    @Test
    void edgesRequireBothEndpointsInside() {
        // Edge 0: (50,50)→(60,50) fully inside; edge 1: (50,50)→(95,50) one endpoint outside
        float[] edgePositions = {
            0.0f, 0.0f, 0.0f,   0.2f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,   0.9f, 0.0f, 0.0f
        };

        float[] rect = BoxSelectMath.normalizeRect(40, 40, 70, 60);
        int[] hits = BoxSelectMath.edgesInRect(edgePositions, 2, IDENTITY, VIEWPORT, VIEWPORT, rect);
        assertArrayEquals(new int[]{0}, hits);
    }

    @Test
    void edgeWithEndpointBehindCameraIsExcluded() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.1f, 100f);

        // One endpoint in front, one behind the camera
        float[] edgePositions = {
            0.0f, 0.0f, -5.0f,   0.0f, 0.0f, 5.0f
        };

        float[] hugeRect = BoxSelectMath.normalizeRect(-1000, -1000, 1000, 1000);
        assertEquals(0, BoxSelectMath.edgesInRect(edgePositions, 1, perspective, VIEWPORT, VIEWPORT, hugeRect).length);
    }

    // ── Faces ─────────────────────────────────────────────────────────────

    @Test
    void facesSelectByCentroid() {
        // Face 7: quad centered at origin → centroid screen (50,50)
        // Face 9: quad centered at (0.8, 0.8) → centroid screen (90,10)
        Vector3f[] centered = {
            new Vector3f(-0.1f, -0.1f, 0), new Vector3f(0.1f, -0.1f, 0),
            new Vector3f(0.1f, 0.1f, 0), new Vector3f(-0.1f, 0.1f, 0)
        };
        Vector3f[] offset = {
            new Vector3f(0.7f, 0.7f, 0), new Vector3f(0.9f, 0.7f, 0),
            new Vector3f(0.9f, 0.9f, 0), new Vector3f(0.7f, 0.9f, 0)
        };

        int[] faceIds = {7, 9};
        float[] rect = BoxSelectMath.normalizeRect(40, 40, 60, 60);
        int[] hits = BoxSelectMath.facesInRect(faceIds,
            id -> id == 7 ? centered : offset,
            IDENTITY, VIEWPORT, VIEWPORT, rect);
        assertArrayEquals(new int[]{7}, hits);

        // A rect containing only the centroid still selects the face even if
        // its corners are outside (centroid rule)
        float[] tight = BoxSelectMath.normalizeRect(49, 49, 51, 51);
        assertArrayEquals(new int[]{7}, BoxSelectMath.facesInRect(faceIds,
            id -> id == 7 ? centered : offset,
            IDENTITY, VIEWPORT, VIEWPORT, tight));
    }

    @Test
    void facesWithMissingPositionsAreSkipped() {
        int[] faceIds = {1, 2};
        float[] rect = BoxSelectMath.normalizeRect(0, 0, 100, 100);

        Vector3f[] valid = {
            new Vector3f(0, 0, 0), new Vector3f(0.1f, 0, 0), new Vector3f(0.1f, 0.1f, 0)
        };
        int[] hits = BoxSelectMath.facesInRect(faceIds,
            id -> id == 1 ? valid : null,
            IDENTITY, VIEWPORT, VIEWPORT, rect);
        assertArrayEquals(new int[]{1}, hits);
    }

    // ── Degenerate inputs ─────────────────────────────────────────────────

    @Test
    void nullInputsYieldEmptyResults() {
        float[] rect = {0f, 0f, 100f, 100f};
        assertEquals(0, BoxSelectMath.verticesInRect(null, 3, IDENTITY, VIEWPORT, VIEWPORT, rect).length);
        assertEquals(0, BoxSelectMath.edgesInRect(null, 3, IDENTITY, VIEWPORT, VIEWPORT, rect).length);
        assertEquals(0, BoxSelectMath.facesInRect(null, id -> null, IDENTITY, VIEWPORT, VIEWPORT, rect).length);
        assertEquals(0, BoxSelectMath.verticesInRect(new float[]{0, 0, 0}, 1, null, VIEWPORT, VIEWPORT, rect).length);
    }
}
