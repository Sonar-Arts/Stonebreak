package com.stonebreak.world.generation.spline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-dimensional spline using recursive nested 1D splines.
 * <p>
 * This class implements a spline-of-splines architecture where each dimension
 * is handled by nesting splines within splines:
 * <ul>
 *   <li>1D spline: f(x) - standard interpolation between constant values</li>
 *   <li>2D spline: f(x, y) - each x point contains a 1D spline for y</li>
 *   <li>3D spline: f(x, y, z) - each x point contains a 2D spline for (y, z)</li>
 *   <li>4D spline: f(x, y, z, w) - each x point contains a 3D spline for (y, z, w)</li>
 * </ul>
 * <p>
 * This approach is simpler than true multi-dimensional interpolation while still
 * providing smooth transitions across all parameters.
 * <p>
 * Example usage for terrain generation:
 * <pre>{@code
 * // Create a 4D spline: height = f(continentalness, erosion, PV, weirdness)
 * MultiDimensionalSpline spline = new MultiDimensionalSpline();
 *
 * // Ocean terrain: low continentalness, any erosion
 * MultiDimensionalSpline oceanErosion = new MultiDimensionalSpline();
 * oceanErosion.addPoint(-1.0f, 40.0f); // Deep ocean at high erosion
 * oceanErosion.addPoint(1.0f, 50.0f);  // Shallow ocean at low erosion
 * spline.addPoint(-0.8f, oceanErosion);
 *
 * // Mountain terrain: high continentalness, low erosion
 * MultiDimensionalSpline mountainErosion = new MultiDimensionalSpline();
 * mountainErosion.addPoint(-1.0f, 160.0f); // Extreme peaks
 * mountainErosion.addPoint(1.0f, 80.0f);   // Rolling hills
 * spline.addPoint(0.8f, mountainErosion);
 *
 * // Sample: height at continentalness=0.5, erosion=-0.3
 * float height = spline.sample(0.5f, -0.3f);
 * }</pre>
 *
 * @see com.stonebreak.world.generation.spline.TerrainSplineRouter
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
     *
     * @param location Location on the primary axis (typically -1.0 to 1.0 for noise parameters)
     * @param value Constant value at this location
     */
    public void addPoint(float location, float value) {
        points.add(new SplinePoint(location, value));
        Collections.sort(points);
    }

    /**
     * Add a spline point with a nested spline (branch node).
     * Used for multi-dimensional interpolation where this point contains
     * a spline for the remaining dimensions.
     *
     * @param location Location on the primary axis (typically -1.0 to 1.0 for noise parameters)
     * @param nestedSpline Spline for the remaining dimensions
     */
    public void addPoint(float location, MultiDimensionalSpline nestedSpline) {
        points.add(new SplinePoint(location, nestedSpline));
        Collections.sort(points);
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

        // Linear interpolation factor between the two points
        float t = (primary - p1.location) / (p2.location - p1.location);

        // Get values from both points (recursively samples nested splines if present)
        float value1 = p1.getValue(nested);
        float value2 = p2.getValue(nested);

        // Linear interpolation between the two values
        return value1 + t * (value2 - value1);
    }

    /**
     * A single point in the spline.
     * Can be either a leaf node (constant value) or branch node (nested spline).
     */
    private static class SplinePoint implements Comparable<SplinePoint> {
        private final float location;
        private final Float constantValue; // Null if this is a branch node
        private final MultiDimensionalSpline nestedSpline; // Null if this is a leaf node

        /**
         * Create a leaf node with a constant value.
         */
        SplinePoint(float location, float value) {
            this.location = location;
            this.constantValue = value;
            this.nestedSpline = null;
        }

        /**
         * Create a branch node with a nested spline.
         */
        SplinePoint(float location, MultiDimensionalSpline nestedSpline) {
            this.location = location;
            this.constantValue = null;
            this.nestedSpline = nestedSpline;
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
