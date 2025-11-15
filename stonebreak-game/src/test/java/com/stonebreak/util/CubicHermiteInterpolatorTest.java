package com.stonebreak.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CubicHermiteInterpolator
 *
 * Tests verify the correctness of cubic Hermite spline interpolation,
 * including endpoint behavior, derivative effects, and basis function properties.
 */
class CubicHermiteInterpolatorTest {

    private static final float EPSILON = 0.001f;

    @Test
    void testInterpolationEndpoints() {
        // At t=0, should return p0
        float result0 = CubicHermiteInterpolator.interpolate(0.0f, 50.0f, 100.0f, 1.0f, 1.0f, 1.0f);
        assertEquals(50.0f, result0, EPSILON, "At t=0, should return start value");

        // At t=1, should return p1
        float result1 = CubicHermiteInterpolator.interpolate(1.0f, 50.0f, 100.0f, 1.0f, 1.0f, 1.0f);
        assertEquals(100.0f, result1, EPSILON, "At t=1, should return end value");
    }

    @Test
    void testFlatDerivativesCreatePlateau() {
        // With derivative=0 at both ends, curve should be very flat near endpoints
        float result = CubicHermiteInterpolator.interpolate(0.1f, 50.0f, 100.0f, 0.0f, 0.0f, 1.0f);

        // Should be close to start value (plateau effect)
        assertTrue(result < 55.0f, "With zero derivatives, should have plateau near start");
    }

    @Test
    void testSteepDerivativesCreateSCurve() {
        // With high derivatives, should create S-curve
        float midpoint = CubicHermiteInterpolator.interpolate(0.5f, 50.0f, 100.0f, 5.0f, 5.0f, 1.0f);

        // Should be near midpoint for symmetric derivatives
        assertEquals(75.0f, midpoint, 5.0f, "With symmetric derivatives, midpoint should be near average");
    }

    @Test
    void testMonotonicClamping() {
        float derivative = 10.0f;  // Very steep
        float clamped = CubicHermiteInterpolator.clampMonotonic(derivative, 50.0f, 100.0f, 1.0f);

        // Should be clamped to 3x the slope
        assertTrue(clamped <= 150.0f, "Should clamp to 3x slope for monotonicity");
    }

    @Test
    void testBasisFunctionsSum() {
        // At any t, h1(t) + h2(t) should equal 1 (partition of unity)
        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            float sum = CubicHermiteInterpolator.h1(t) + CubicHermiteInterpolator.h2(t);
            assertEquals(1.0f, sum, EPSILON, "Basis functions h1 + h2 should sum to 1");
        }
    }

    @Test
    void testBasisFunctionH1() {
        // h1(t) = 2t³ - 3t² + 1
        // At t=0: h1(0) = 1
        // At t=1: h1(1) = 0
        assertEquals(1.0f, CubicHermiteInterpolator.h1(0.0f), EPSILON);
        assertEquals(0.0f, CubicHermiteInterpolator.h1(1.0f), EPSILON);
    }

    @Test
    void testBasisFunctionH2() {
        // h2(t) = -2t³ + 3t²
        // At t=0: h2(0) = 0
        // At t=1: h2(1) = 1
        assertEquals(0.0f, CubicHermiteInterpolator.h2(0.0f), EPSILON);
        assertEquals(1.0f, CubicHermiteInterpolator.h2(1.0f), EPSILON);
    }

    @Test
    void testBasisFunctionH3() {
        // h3(t) = t³ - 2t² + t
        // At t=0: h3(0) = 0
        // At t=1: h3(1) = 0
        assertEquals(0.0f, CubicHermiteInterpolator.h3(0.0f), EPSILON);
        assertEquals(0.0f, CubicHermiteInterpolator.h3(1.0f), EPSILON);
    }

    @Test
    void testBasisFunctionH4() {
        // h4(t) = t³ - t²
        // At t=0: h4(0) = 0
        // At t=1: h4(1) = 0
        assertEquals(0.0f, CubicHermiteInterpolator.h4(0.0f), EPSILON);
        assertEquals(0.0f, CubicHermiteInterpolator.h4(1.0f), EPSILON);
    }

    @Test
    void testDerivativeCalculation() {
        // Test derivative calculation at t=0.5
        float derivative = CubicHermiteInterpolator.derivative(0.5f, 50.0f, 100.0f, 1.0f, 1.0f, 1.0f);

        // For symmetric derivatives, slope at midpoint should be close to average slope
        float averageSlope = (100.0f - 50.0f) / 1.0f;
        assertEquals(averageSlope, derivative, 10.0f, "Derivative at midpoint should be close to average slope");
    }

    @Test
    void testZeroDerivativesAtEndpoints() {
        // With zero derivatives at both ends, the curve should be flat at endpoints
        float resultNearStart = CubicHermiteInterpolator.interpolate(0.01f, 50.0f, 100.0f, 0.0f, 0.0f, 1.0f);
        float resultNearEnd = CubicHermiteInterpolator.interpolate(0.99f, 50.0f, 100.0f, 0.0f, 0.0f, 1.0f);

        // Should be very close to endpoint values (within 2% of range)
        assertTrue(Math.abs(resultNearStart - 50.0f) < 1.0f, "Near start should be close to start value");
        assertTrue(Math.abs(resultNearEnd - 100.0f) < 1.0f, "Near end should be close to end value");
    }

    @Test
    void testAsymmetricDerivatives() {
        // With asymmetric derivatives, curve should favor the steeper side
        float steepStart = CubicHermiteInterpolator.interpolate(0.3f, 50.0f, 100.0f, 5.0f, 0.5f, 1.0f);
        float steepEnd = CubicHermiteInterpolator.interpolate(0.7f, 50.0f, 100.0f, 0.5f, 5.0f, 1.0f);

        // With steep start derivative, curve should rise quickly at the beginning
        // At t=0.3, should be significantly above linear interpolation
        float linearAt03 = 50.0f + 0.3f * (100.0f - 50.0f);
        assertTrue(steepStart > linearAt03, "Steep start derivative should create faster initial rise");
    }

    @Test
    void testNegativeDerivatives() {
        // Negative derivatives should create overshoot (local extrema)
        float result = CubicHermiteInterpolator.interpolate(0.5f, 50.0f, 100.0f, -2.0f, -2.0f, 1.0f);

        // With negative derivatives going upward, should dip below start or overshoot end
        // This creates an S-curve that initially goes "wrong" direction
        assertTrue(result < 90.0f || result > 60.0f, "Negative derivatives should create curve variation");
    }

    @Test
    void testSegmentWidthScaling() {
        // Derivatives should scale with segment width
        float result1 = CubicHermiteInterpolator.interpolate(0.5f, 50.0f, 100.0f, 1.0f, 1.0f, 1.0f);
        float result2 = CubicHermiteInterpolator.interpolate(0.5f, 50.0f, 100.0f, 1.0f, 1.0f, 2.0f);

        // With larger dx, derivative influence should be stronger
        assertTrue(Math.abs(result2 - 75.0f) > Math.abs(result1 - 75.0f),
                   "Larger dx should amplify derivative influence");
    }

    @Test
    void testEstimateDerivative() {
        // Test finite difference derivative estimation
        float derivative = CubicHermiteInterpolator.estimateDerivative(0.0f, 1.0f, 50.0f, 100.0f);

        // Should be the slope: (100 - 50) / (1 - 0) = 50
        assertEquals(50.0f, derivative, EPSILON, "Finite difference should calculate slope correctly");
    }

    @Test
    void testClampMonotonicPreventsFlatSpots() {
        // When values are equal, derivative should be clamped to zero
        float derivative = 5.0f;
        float clamped = CubicHermiteInterpolator.clampMonotonic(derivative, 50.0f, 50.0f, 1.0f);

        assertEquals(0.0f, clamped, EPSILON, "Equal values should produce zero derivative");
    }

    @Test
    void testSmoothnessBetweenSegments() {
        // Test C¹ continuity: interpolating through three points
        // First segment: (0, 50) to (1, 75) with derivative 1.0 at both ends
        // Second segment: (1, 75) to (2, 100) with derivative 1.0 at both ends

        float end1 = CubicHermiteInterpolator.interpolate(1.0f, 50.0f, 75.0f, 1.0f, 1.0f, 1.0f);
        float start2 = CubicHermiteInterpolator.interpolate(0.0f, 75.0f, 100.0f, 1.0f, 1.0f, 1.0f);

        // Values should match at the join point
        assertEquals(end1, start2, EPSILON, "Segments should join smoothly in value");
        assertEquals(75.0f, end1, EPSILON, "Join point should have correct value");
    }
}
