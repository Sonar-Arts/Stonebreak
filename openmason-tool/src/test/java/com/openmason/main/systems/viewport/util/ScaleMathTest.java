package com.openmason.main.systems.viewport.util;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScaleMathTest {

    private static final float EPS = 1e-5f;

    @Test
    void referenceDistanceIsEuclideanDistanceToPivot() {
        Vector2f pivot = new Vector2f(100, 100);
        assertEquals(50f, ScaleMath.referenceDistance(150, 100, pivot), EPS);
        assertEquals(50f, ScaleMath.referenceDistance(130, 140, pivot), EPS); // 3-4-5 triangle
    }

    @Test
    void referenceDistanceClampsToMinimum() {
        Vector2f pivot = new Vector2f(100, 100);

        // Mouse on top of the pivot must not produce a near-zero reference
        assertEquals(ScaleMath.MIN_REFERENCE_DISTANCE_PX,
            ScaleMath.referenceDistance(100, 100, pivot), EPS);
        assertEquals(ScaleMath.MIN_REFERENCE_DISTANCE_PX,
            ScaleMath.referenceDistance(102, 100, pivot), EPS);
    }

    @Test
    void factorIsDistanceRatio() {
        Vector2f pivot = new Vector2f(100, 100);
        float d0 = 50f;

        assertEquals(1f, ScaleMath.factor(150, 100, pivot, d0), EPS);   // Same distance
        assertEquals(2f, ScaleMath.factor(200, 100, pivot, d0), EPS);   // Twice as far
        assertEquals(0.5f, ScaleMath.factor(125, 100, pivot, d0), EPS); // Half as far
        assertEquals(0f, ScaleMath.factor(100, 100, pivot, d0), EPS);   // On the pivot
    }

    @Test
    void scaleAboutPivotGrowsAndShrinksAroundPivot() {
        Vector3f pivot = new Vector3f(1, 1, 1);
        Vector3f original = new Vector3f(3, 1, 1); // Offset (2, 0, 0) from pivot

        Vector3f doubled = ScaleMath.scaleAboutPivot(original, pivot, 2f);
        assertEquals(5f, doubled.x, EPS);
        assertEquals(1f, doubled.y, EPS);
        assertEquals(1f, doubled.z, EPS);

        Vector3f halved = ScaleMath.scaleAboutPivot(original, pivot, 0.5f);
        assertEquals(2f, halved.x, EPS);

        // Factor 0 collapses onto the pivot
        Vector3f collapsed = ScaleMath.scaleAboutPivot(original, pivot, 0f);
        assertEquals(pivot.x, collapsed.x, EPS);
        assertEquals(pivot.y, collapsed.y, EPS);
        assertEquals(pivot.z, collapsed.z, EPS);
    }

    @Test
    void scaleAboutPivotLeavesPivotPointAndInputsUntouched() {
        Vector3f pivot = new Vector3f(2, -3, 4);
        Vector3f original = new Vector3f(2, -3, 4); // Point AT the pivot never moves

        Vector3f scaled = ScaleMath.scaleAboutPivot(original, pivot, 7f);
        assertEquals(pivot.x, scaled.x, EPS);
        assertEquals(pivot.y, scaled.y, EPS);
        assertEquals(pivot.z, scaled.z, EPS);

        // Inputs are not mutated
        assertEquals(2f, original.x, EPS);
        assertEquals(2f, pivot.x, EPS);
    }

    @Test
    void factorOneIsIdentity() {
        Vector3f pivot = new Vector3f(1, 2, 3);
        Vector3f original = new Vector3f(-4, 5, -6);

        Vector3f scaled = ScaleMath.scaleAboutPivot(original, pivot, 1f);
        assertEquals(original.x, scaled.x, EPS);
        assertEquals(original.y, scaled.y, EPS);
        assertEquals(original.z, scaled.z, EPS);
    }
}
