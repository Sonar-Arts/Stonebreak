package com.openmason.main.systems.viewport.util;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EdgeCutMathTest {

    private static final float EPS = 1e-5f;

    @Test
    void pointOnEdgeInterpolatesEndpoints() {
        Vector3f a = new Vector3f(0, 0, 0);
        Vector3f b = new Vector3f(4, 2, -2);

        Vector3f atStart = EdgeCutMath.pointOnEdge(a, b, 0f);
        assertEquals(0f, atStart.x, EPS);
        assertEquals(0f, atStart.y, EPS);
        assertEquals(0f, atStart.z, EPS);

        Vector3f atEnd = EdgeCutMath.pointOnEdge(a, b, 1f);
        assertEquals(4f, atEnd.x, EPS);
        assertEquals(2f, atEnd.y, EPS);
        assertEquals(-2f, atEnd.z, EPS);

        Vector3f mid = EdgeCutMath.pointOnEdge(a, b, 0.5f);
        assertEquals(2f, mid.x, EPS);
        assertEquals(1f, mid.y, EPS);
        assertEquals(-1f, mid.z, EPS);

        Vector3f quarter = EdgeCutMath.pointOnEdge(a, b, 0.25f);
        assertEquals(1f, quarter.x, EPS);
        assertEquals(0.5f, quarter.y, EPS);
        assertEquals(-0.5f, quarter.z, EPS);
    }

    @Test
    void recomputeTRoundTripsWithPointOnEdge() {
        Vector3f a = new Vector3f(-1, 2, 3);
        Vector3f b = new Vector3f(5, -4, 1);

        for (float t = 0f; t <= 1f; t += 0.125f) {
            Vector3f point = EdgeCutMath.pointOnEdge(a, b, t);
            assertEquals(t, EdgeCutMath.recomputeT(point, a, b), EPS);
        }
    }

    @Test
    void recomputeTClampsToUnitRange() {
        Vector3f a = new Vector3f(0, 0, 0);
        Vector3f b = new Vector3f(1, 0, 0);

        // Point past B along the edge direction clamps to 1
        assertEquals(1f, EdgeCutMath.recomputeT(new Vector3f(5, 0, 0), a, b), EPS);

        // Point before A clamps to 0
        assertEquals(0f, EdgeCutMath.recomputeT(new Vector3f(-5, 0, 0), a, b), EPS);
    }

    @Test
    void recomputeTProjectsOffAxisPointsOntoEdge() {
        Vector3f a = new Vector3f(0, 0, 0);
        Vector3f b = new Vector3f(2, 0, 0);

        // Point above the midpoint projects to t = 0.5
        assertEquals(0.5f, EdgeCutMath.recomputeT(new Vector3f(1, 3, 0), a, b), EPS);
    }

    @Test
    void zeroLengthEdgeReturnsHalf() {
        Vector3f a = new Vector3f(1, 1, 1);
        Vector3f b = new Vector3f(1, 1, 1);

        assertEquals(0.5f, EdgeCutMath.recomputeT(new Vector3f(7, 8, 9), a, b), EPS);
    }
}
