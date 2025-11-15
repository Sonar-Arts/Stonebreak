package com.stonebreak.world.generation.spline;

import com.stonebreak.util.CubicHermiteInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-dimensional spline using recursive nested 1D cubic Hermite splines.
 *
 * <p>This class implements a spline-of-splines architecture where each dimension
 * is handled by nesting splines within splines. Each interpolation uses cubic
 * Hermite splines for smooth C^1 continuity (continuous values AND slopes).</p>
 *
 * <h2>Architecture</h2>
 * <p>The recursive nesting pattern:</p>
 * <ul>
 *   <li><b>1D spline</b>: f(x) - standard interpolation between constant values</li>
 *   <li><b>2D spline</b>: f(x, y) - each x point contains a 1D spline for y</li>
 *   <li><b>3D spline</b>: f(x, y, z) - each x point contains a 2D spline for (y, z)</li>
 *   <li><b>4D spline</b>: f(x, y, z, w) - each x point contains a 3D spline for (y, z, w)</li>
 * </ul>
 *
 * <p>This approach is simpler than true multi-dimensional tensor product interpolation
 * while still providing smooth transitions across all parameters. It's particularly
 * well-suited for terrain generation where parameters have hierarchical relationships
 * (e.g., continentalness determines the broad terrain type, then erosion refines it).</p>
 *
 * <h2>Cubic Hermite Interpolation</h2>
 * <p>Unlike linear interpolation, this class uses cubic Hermite splines which
 * interpolate using both values AND derivatives at control points. This creates:</p>
 * <ul>
 *   <li>Smooth curves with no sharp corners (C^1 continuity)</li>
 *   <li>Plateaus when derivative = 0.0 (flat mesa tops, ocean floors)</li>
 *   <li>Steep slopes when derivative &gt; 2.0 (cliffs, mountain sides)</li>
 *   <li>Gentle transitions when derivative = 1.0-1.5 (rolling hills)</li>
 * </ul>
 *
 * <h2>Example: 3D Terrain Spline</h2>
 * <pre>{@code
 * // Create a 3D spline: height = f(continentalness, erosion, PV)
 * MultiDimensionalSpline continentalnessSpline = new MultiDimensionalSpline();
 *
 * // Ocean terrain: low continentalness (-0.8)
 * MultiDimensionalSpline oceanErosion = new MultiDimensionalSpline();
 * // Low erosion (mountains) → shallow ocean (50 blocks)
 * oceanErosion.addPoint(-1.0f, buildPVSpline(45.0f, 50.0f), 1.0f);
 * // High erosion (flat) → deep ocean (40 blocks)
 * oceanErosion.addPoint(1.0f, buildPVSpline(35.0f, 40.0f), 0.0f); // Flat derivative
 * continentalnessSpline.addPoint(-0.8f, oceanErosion, 0.5f); // Gentle ocean transition
 *
 * // Mountain terrain: high continentalness (0.8)
 * MultiDimensionalSpline mountainErosion = new MultiDimensionalSpline();
 * // Low erosion (mountains) → extreme peaks (160 blocks)
 * mountainErosion.addPoint(-1.0f, buildPVSpline(150.0f, 170.0f), 0.0f); // Flat peak
 * // High erosion (flat) → rolling hills (80 blocks)
 * mountainErosion.addPoint(1.0f, buildPVSpline(75.0f, 85.0f), 0.0f); // Flat plains
 * continentalnessSpline.addPoint(0.8f, mountainErosion, 2.5f); // Steep mountain rise
 *
 * // Sample: height at continentalness=0.5, erosion=-0.3, PV=0.0
 * float height = continentalnessSpline.sample(0.5f, -0.3f, 0.0f);
 * }</pre>
 *
 * <h2>Control Point Design</h2>
 * <p>When designing splines for terrain:</p>
 * <ul>
 *   <li>Use 7-10 points for primary dimension (continentalness)</li>
 *   <li>Use 5-7 points for secondary dimension (erosion)</li>
 *   <li>Use 3 points for tertiary dimension (PV: valley, neutral, peak)</li>
 *   <li>Set derivative = 0.0 for plateaus (mesa tops, flat peaks, ocean floors)</li>
 *   <li>Set derivative = 1.0-1.5 for smooth transitions</li>
 *   <li>Set derivative = 2.0-3.0 for steep slopes</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><b>Sampling</b>: O(log n) per dimension with binary search (or O(n) with linear search)</li>
 *   <li><b>Memory</b>: O(total control points) - typically ~200-500 points for 3D terrain</li>
 *   <li><b>Construction</b>: O(n log n) per dimension due to sorting</li>
 * </ul>
 *
 * @see CubicHermiteInterpolator
 * @see com.stonebreak.world.generation.spline.OffsetSplineRouter
 * @see com.stonebreak.world.generation.spline.JaggednessSplineRouter
 * @see com.stonebreak.world.generation.spline.FactorSplineRouter
 */
public class MultiDimensionalSpline {

    private final List<SplinePoint> points;

    /**
     * Create a new empty multi-dimensional spline.
     */
    public MultiDimensionalSpline() {
        this.points = new ArrayList<>();
    }

    /**
     * Add a spline point with a constant value (leaf node).
     * Used for the final dimension when no further nesting is needed.
     * Uses default derivative of 1.0 for smooth transitions.
     *
     * @param location Location on the primary axis (typically -1.0 to 1.0 for noise parameters)
     * @param value Constant value at this location
     */
    public void addPoint(float location, float value) {
        points.add(new SplinePoint(location, value, 1.0f));
        Collections.sort(points);
    }

    /**
     * Add a spline point with a nested spline (branch node).
     * Used for multi-dimensional interpolation where this point contains
     * a spline for the remaining dimensions.
     * Uses default derivative of 1.0 for smooth transitions.
     *
     * @param location Location on the primary axis (typically -1.0 to 1.0 for noise parameters)
     * @param nestedSpline Spline for the remaining dimensions
     */
    public void addPoint(float location, MultiDimensionalSpline nestedSpline) {
        points.add(new SplinePoint(location, nestedSpline, 1.0f));
        Collections.sort(points);
    }

    /**
     * Add a control point with a constant value and derivative.
     * The derivative controls the steepness of the curve at this point.
     *
     * @param location Location on the primary axis
     * @param value Constant value at this location
     * @param derivative Slope/tangent at this point (0.0 = flat/plateau, higher = steeper)
     */
    public void addPoint(float location, float value, float derivative) {
        points.add(new SplinePoint(location, value, derivative));
        Collections.sort(points);
    }

    /**
     * Add a control point with a nested spline and derivative.
     *
     * @param location Location on the primary axis
     * @param nestedSpline Spline for the remaining dimensions
     * @param derivative Slope/tangent at this point
     */
    public void addPoint(float location, MultiDimensionalSpline nestedSpline, float derivative) {
        points.add(new SplinePoint(location, nestedSpline, derivative));
        Collections.sort(points);
    }

    /**
     * Add a control point with automatic derivative calculation (simple finite difference).
     * Use this when you don't want to manually specify derivatives.
     * Call {@link #calculateAutomaticDerivatives()} after all points are added.
     *
     * @param location Location on the primary axis
     * @param value Constant value at this location
     */
    public void addPointAutoDerivative(float location, float value) {
        addPoint(location, value, 1.0f);
    }

    /**
     * Sample the spline at the given coordinates.
     * <p>
     * The first parameter is the primary coordinate on this spline's axis.
     * Additional parameters are passed to nested splines recursively.
     *
     * @param primary Primary coordinate value (e.g., continentalness)
     * @param nested Additional coordinates for nested dimensions (e.g., erosion, PV, weirdness)
     * @return Interpolated value
     */
    public float sample(float primary, float... nested) {
        if (points.isEmpty()) {
            return 0.0f;
        }

        // Clamp to first point if before range
        if (primary <= points.get(0).location) {
            return points.get(0).getValue(nested);
        }

        // Clamp to last point if after range
        if (primary >= points.get(points.size() - 1).location) {
            return points.get(points.size() - 1).getValue(nested);
        }

        // Find the two points that bracket the primary value
        int i = 0;
        while (i < points.size() - 1 && primary > points.get(i + 1).location) {
            i++;
        }

        SplinePoint p1 = points.get(i);
        SplinePoint p2 = points.get(i + 1);

        // Cubic Hermite interpolation
        float x0 = p1.location;
        float x1 = p2.location;
        float t = (primary - x0) / (x1 - x0);  // Normalize to [0, 1]

        // Get values and derivatives
        float p0 = p1.getValue(nested);
        float p1Val = p2.getValue(nested);
        float m0 = p1.getDerivative();
        float m1 = p2.getDerivative();

        // Scale derivatives by segment width
        float dx = x1 - x0;

        // Cubic Hermite interpolation formula
        return CubicHermiteInterpolator.interpolate(t, p0, p1Val, m0, m1, dx);
    }

    /**
     * Calculate derivatives automatically using finite differences.
     * Call this after all points are added if you used {@link #addPointAutoDerivative(float, float)}.
     * <p>
     * This method recalculates derivatives for all points using:
     * - Forward difference for the first point
     * - Backward difference for the last point
     * - Central difference for middle points
     */
    public void calculateAutomaticDerivatives() {
        if (points.size() < 2) return;

        List<SplinePoint> newPoints = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            SplinePoint current = points.get(i);
            float derivative;

            // Only calculate derivatives for leaf nodes (constant values)
            if (current.constantValue == null) {
                // Branch node: keep existing derivative
                newPoints.add(current);
                continue;
            }

            if (i == 0) {
                // First point: forward difference
                SplinePoint next = points.get(i + 1);
                if (next.constantValue != null) {
                    float dy = next.constantValue - current.constantValue;
                    float dx = next.location - current.location;
                    derivative = dy / dx;
                } else {
                    derivative = 1.0f; // Default for nested spline neighbors
                }
            } else if (i == points.size() - 1) {
                // Last point: backward difference
                SplinePoint prev = points.get(i - 1);
                if (prev.constantValue != null) {
                    float dy = current.constantValue - prev.constantValue;
                    float dx = current.location - prev.location;
                    derivative = dy / dx;
                } else {
                    derivative = 1.0f; // Default for nested spline neighbors
                }
            } else {
                // Middle points: central difference
                SplinePoint prev = points.get(i - 1);
                SplinePoint next = points.get(i + 1);
                if (prev.constantValue != null && next.constantValue != null) {
                    float dy = next.constantValue - prev.constantValue;
                    float dx = next.location - prev.location;
                    derivative = dy / dx;
                } else {
                    derivative = 1.0f; // Default for nested spline neighbors
                }
            }

            // Create new point with calculated derivative
            newPoints.add(new SplinePoint(current.location, current.constantValue, derivative));
        }

        points.clear();
        points.addAll(newPoints);
    }

    /**
     * A single point in the spline.
     * Can be either a leaf node (constant value) or branch node (nested spline).
     */
    private static class SplinePoint implements Comparable<SplinePoint> {
        private final float location;
        private final Float constantValue; // Null if this is a branch node
        private final MultiDimensionalSpline nestedSpline; // Null if this is a leaf node
        private final float derivative;  // Tangent/slope at this point

        /**
         * Create a leaf node with a constant value and derivative.
         */
        SplinePoint(float location, float value, float derivative) {
            this.location = location;
            this.constantValue = value;
            this.nestedSpline = null;
            this.derivative = derivative;
        }

        /**
         * Create a branch node with a nested spline and derivative.
         */
        SplinePoint(float location, MultiDimensionalSpline nestedSpline, float derivative) {
            this.location = location;
            this.constantValue = null;
            this.nestedSpline = nestedSpline;
            this.derivative = derivative;
        }

        /**
         * Get the derivative (slope/tangent) at this point.
         *
         * @return Derivative value
         */
        float getDerivative() {
            return derivative;
        }

        /**
         * Get the value at this point.
         * If this is a leaf node, returns the constant value.
         * If this is a branch node, samples the nested spline with the given parameters.
         *
         * @param nested Parameters for nested spline (ignored for leaf nodes)
         * @return Value at this point
         */
        float getValue(float... nested) {
            if (constantValue != null) {
                // Leaf node: return constant value
                return constantValue;
            } else {
                // Branch node: recursively sample nested spline
                if (nested.length == 0) {
                    // No more dimensions to sample, shouldn't happen if spline is well-formed
                    // Default to sampling with 0.0 for safety
                    return nestedSpline.sample(0.0f);
                } else if (nested.length == 1) {
                    // Final dimension
                    return nestedSpline.sample(nested[0]);
                } else {
                    // Multiple remaining dimensions: pass first as primary, rest as nested
                    float[] remaining = new float[nested.length - 1];
                    System.arraycopy(nested, 1, remaining, 0, nested.length - 1);
                    return nestedSpline.sample(nested[0], remaining);
                }
            }
        }

        @Override
        public int compareTo(SplinePoint other) {
            return Float.compare(this.location, other.location);
        }
    }
}
