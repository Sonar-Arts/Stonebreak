package com.stonebreak.util;

/**
 * Utility class for cubic Hermite spline interpolation.
 *
 * <p>Cubic Hermite splines interpolate between two points using both values
 * and derivatives (slopes/tangents) at those points, creating smooth C^1 curves.
 * Unlike linear interpolation which only guarantees continuous values (C^0),
 * cubic Hermite interpolation also guarantees continuous slopes (C^1), resulting
 * in visually smoother terrain with no sharp corners.</p>
 *
 * <h2>Mathematical Foundation</h2>
 * <p>The interpolation formula combines four pieces of information:</p>
 * <pre>
 * p(t) = h1(t)·p0 + h2(t)·p1 + h3(t)·m0·Δx + h4(t)·m1·Δx
 * </pre>
 *
 * <p>Where:</p>
 * <ul>
 *   <li><b>t ∈ [0, 1]</b> - normalized parameter (0 = start, 1 = end)</li>
 *   <li><b>p0, p1</b> - values at start/end control points</li>
 *   <li><b>m0, m1</b> - derivatives (slopes/tangents) at control points</li>
 *   <li><b>Δx</b> - distance between control points (for derivative scaling)</li>
 *   <li><b>h1-h4</b> - Hermite basis functions (cubic polynomials)</li>
 * </ul>
 *
 * <h2>Derivative Effects on Terrain</h2>
 * <p>The derivative at a control point controls the steepness of the curve:</p>
 * <table border="1">
 *   <tr><th>Derivative</th><th>Visual Effect</th><th>Terrain Use Case</th></tr>
 *   <tr><td>0.0</td><td>Flat tangent (plateau)</td><td>Mesa tops, flat peaks</td></tr>
 *   <tr><td>0.5-1.5</td><td>Gentle slope</td><td>Gradual hills, smooth transitions</td></tr>
 *   <tr><td>2.0-4.0</td><td>Moderate-steep slope</td><td>Mountain slopes, cliffs</td></tr>
 *   <tr><td>&gt; 4.0</td><td>Very steep slope</td><td>Abrupt changes (use sparingly)</td></tr>
 * </table>
 *
 * <h2>Usage Example - Creating a Mesa</h2>
 * <pre>
 * // Create a plateau with steep sides and flat top
 * // Point 1: Desert floor (height 65) with flat derivative
 * float height1 = 65.0f;
 * float derivative1 = 0.0f;  // Flat desert floor
 *
 * // Point 2: Mesa top (height 120) with flat derivative
 * float height2 = 120.0f;
 * float derivative2 = 0.0f;  // Flat mesa top
 *
 * // Interpolate - will create S-curve with flat plateaus at both ends
 * float mesaHeight = CubicHermiteInterpolator.interpolate(t, height1, height2,
 *                                                          derivative1, derivative2, 1.0f);
 * </pre>
 *
 * <h2>Basis Functions</h2>
 * <p>The four Hermite basis functions are cubic polynomials that satisfy:</p>
 * <ul>
 *   <li><b>h1(0) = 1, h1(1) = 0</b> - blends start value (fades out)</li>
 *   <li><b>h2(0) = 0, h2(1) = 1</b> - blends end value (fades in)</li>
 *   <li><b>h3(0) = 0, h3(1) = 0</b> - applies start derivative (bell curve)</li>
 *   <li><b>h4(0) = 0, h4(1) = 0</b> - applies end derivative (bell curve)</li>
 * </ul>
 *
 * <p>Additionally, h1(t) + h2(t) = 1 for all t, which ensures the interpolated
 * value is always a weighted average of the control points.</p>
 *
 * @see com.stonebreak.world.generation.spline.MultiDimensionalSpline
 * @see com.stonebreak.world.generation.spline.OffsetSplineRouter
 */
public class CubicHermiteInterpolator {

    /**
     * Interpolate between two points using cubic Hermite spline
     *
     * @param t Normalized parameter [0, 1]
     * @param p0 Value at start point
     * @param p1 Value at end point
     * @param m0 Derivative at start point
     * @param m1 Derivative at end point
     * @param dx Distance between points (for derivative scaling)
     * @return Interpolated value at parameter t
     */
    public static float interpolate(float t, float p0, float p1, float m0, float m1, float dx) {
        // Precompute powers of t
        float t2 = t * t;
        float t3 = t2 * t;

        // Hermite basis functions
        float h1 = 2*t3 - 3*t2 + 1;      // (2t³ - 3t² + 1)
        float h2 = -2*t3 + 3*t2;         // (-2t³ + 3t²)
        float h3 = t3 - 2*t2 + t;        // (t³ - 2t² + t)
        float h4 = t3 - t2;              // (t³ - t²)

        // Cubic Hermite formula
        return h1 * p0 + h2 * p1 + h3 * m0 * dx + h4 * m1 * dx;
    }

    /**
     * Calculate the first basis function h1(t) = 2t³ - 3t² + 1
     * This blends the start value
     */
    public static float h1(float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 2*t3 - 3*t2 + 1;
    }

    /**
     * Calculate the second basis function h2(t) = -2t³ + 3t²
     * This blends the end value
     */
    public static float h2(float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return -2*t3 + 3*t2;
    }

    /**
     * Calculate the third basis function h3(t) = t³ - 2t² + t
     * This blends the start derivative
     */
    public static float h3(float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return t3 - 2*t2 + t;
    }

    /**
     * Calculate the fourth basis function h4(t) = t³ - t²
     * This blends the end derivative
     */
    public static float h4(float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return t3 - t2;
    }

    /**
     * Calculate derivative of Hermite spline at parameter t
     * Useful for understanding slope at any point along the curve
     */
    public static float derivative(float t, float p0, float p1, float m0, float m1, float dx) {
        float t2 = t * t;

        // Derivatives of basis functions
        float dh1 = 6*t2 - 6*t;          // d/dt(2t³ - 3t² + 1)
        float dh2 = -6*t2 + 6*t;         // d/dt(-2t³ + 3t²)
        float dh3 = 3*t2 - 4*t + 1;      // d/dt(t³ - 2t² + t)
        float dh4 = 3*t2 - 2*t;          // d/dt(t³ - t²)

        return (dh1 * p0 + dh2 * p1 + dh3 * m0 * dx + dh4 * m1 * dx) / dx;
    }

    /**
     * Estimate derivative using finite differences (fallback method)
     * Use this when derivative information is not available
     */
    public static float estimateDerivative(float x0, float x1, float y0, float y1) {
        return (y1 - y0) / (x1 - x0);
    }

    /**
     * Clamp derivative to prevent overshooting
     * Useful for monotonic interpolation (no local extrema between points)
     */
    public static float clampMonotonic(float derivative, float p0, float p1, float dx) {
        float delta = (p1 - p0) / dx;

        // If values are equal, derivative should be zero
        if (Math.abs(delta) < 0.001f) {
            return 0.0f;
        }

        // Clamp to 3 times the slope for monotonicity
        float maxDerivative = 3.0f * Math.abs(delta);
        return Math.max(-maxDerivative, Math.min(maxDerivative, derivative));
    }
}
