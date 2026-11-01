package com.openmason.engine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link SplineInterpolator}: linear interpolation between sorted knots
 * with clamping at both ends.
 */
class SplineInterpolatorTest {

    private static final double DELTA = 1e-9;

    @Test
    void emptyInterpolatorReturnsZero() {
        SplineInterpolator spl = new SplineInterpolator();
        assertEquals(0.0, spl.interpolate(5.0), DELTA);
    }

    @Test
    void clampsBelowFirstKnotAndAboveLastKnot() {
        SplineInterpolator spl = new SplineInterpolator();
        spl.addPoint(0, 10);
        spl.addPoint(10, 20);

        assertEquals(10.0, spl.interpolate(-5), DELTA, "below first knot clamps to first y");
        assertEquals(10.0, spl.interpolate(0), DELTA,  "at first knot returns first y");
        assertEquals(20.0, spl.interpolate(10), DELTA, "at last knot returns last y");
        assertEquals(20.0, spl.interpolate(99), DELTA, "above last knot clamps to last y");
    }

    @Test
    void returnsExactValuesAtKnots() {
        SplineInterpolator spl = new SplineInterpolator();
        spl.addPoint(0, 0);
        spl.addPoint(5, 50);
        spl.addPoint(10, 100);

        assertEquals(0.0, spl.interpolate(0), DELTA);
        assertEquals(50.0, spl.interpolate(5), DELTA);
        assertEquals(100.0, spl.interpolate(10), DELTA);
    }

    @Test
    void interpolatesLinearlyBetweenKnots() {
        SplineInterpolator spl = new SplineInterpolator();
        spl.addPoint(0, 0);
        spl.addPoint(10, 100);

        assertEquals(25.0, spl.interpolate(2.5), DELTA);
        assertEquals(50.0, spl.interpolate(5.0), DELTA);
        assertEquals(75.0, spl.interpolate(7.5), DELTA);
    }

    @Test
    void insertionOrderDoesNotMatter() {
        SplineInterpolator a = new SplineInterpolator();
        a.addPoint(0, 0);
        a.addPoint(5, 50);
        a.addPoint(10, 100);

        SplineInterpolator b = new SplineInterpolator();
        b.addPoint(10, 100);
        b.addPoint(0, 0);
        b.addPoint(5, 50);

        // Check several sample points.
        assertEquals(a.interpolate(-1), b.interpolate(-1), DELTA);
        assertEquals(a.interpolate(2.5), b.interpolate(2.5), DELTA);
        assertEquals(a.interpolate(5.0), b.interpolate(5.0), DELTA);
        assertEquals(a.interpolate(7.5), b.interpolate(7.5), DELTA);
        assertEquals(a.interpolate(10), b.interpolate(10), DELTA);
        assertEquals(a.interpolate(15), b.interpolate(15), DELTA);
    }

    @Test
    void multiSegmentSelectionUsesTheCorrectSegment() {
        SplineInterpolator spl = new SplineInterpolator();
        spl.addPoint(0, 0);
        spl.addPoint(10, 100);
        spl.addPoint(20, 0);

        // Midpoint between (10,100) and (20,0).
        assertEquals(50.0, spl.interpolate(15), DELTA);
    }
}