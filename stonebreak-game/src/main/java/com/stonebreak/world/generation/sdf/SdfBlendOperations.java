package com.stonebreak.world.generation.sdf;

/**
 * Constructive Solid Geometry (CSG) operations for combining SDF primitives.
 *
 * <p>CSG operations allow complex shapes to be built from simple primitives using
 * set operations (union, intersection, subtraction). These operations work on
 * signed distance values, not geometric meshes, making them extremely fast.</p>
 *
 * <p><b>Performance:</b> Each operation is 1-3 floating point operations (min/max/arithmetic).
 * This is negligible compared to SDF primitive evaluation cost.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * // Create a cave: terrain minus sphere
 * float terrainDist = terrainSdf.evaluate(x, y, z);
 * float sphereDist = sphereSdf.evaluate(x, y, z);
 * float resultDist = SdfBlendOperations.subtract(terrainDist, sphereDist);
 *
 * // Create connected caves: union of multiple cave primitives
 * float cave1 = sphere1.evaluate(x, y, z);
 * float cave2 = sphere2.evaluate(x, y, z);
 * float caves = SdfBlendOperations.union(cave1, cave2);
 * </pre>
 *
 * <p><b>Smooth Operations:</b></p>
 * <p>Smooth variants use polynomial interpolation to blend shapes organically,
 * creating natural-looking terrain features without sharp edges. The smoothness
 * parameter k controls the blend radius (typically 2-16 blocks for terrain).</p>
 *
 * <p><b>Thread Safety:</b> All methods are pure functions with no state.</p>
 */
public final class SdfBlendOperations {

    private SdfBlendOperations() {
        // Utility class, no instantiation
    }

    /**
     * Union of two SDFs (set A ∪ B).
     *
     * <p>The result is solid where either shape is solid. This combines shapes
     * by taking the minimum distance (closest surface).</p>
     *
     * <p><b>Use Cases:</b> Combining multiple cave chambers, merging terrain features.</p>
     *
     * @param d1 Signed distance from first primitive
     * @param d2 Signed distance from second primitive
     * @return Minimum distance (negative = inside union)
     */
    public static float union(float d1, float d2) {
        return Math.min(d1, d2);
    }

    /**
     * Subtraction of two SDFs (set A \ B).
     *
     * <p>The result is solid where A is solid but B is not. This carves B out of A.
     * Equivalent to intersecting A with the complement of B.</p>
     *
     * <p><b>Use Cases:</b> Carving caves out of terrain, creating arches.</p>
     *
     * @param d1 Signed distance from base shape (terrain)
     * @param d2 Signed distance from subtracted shape (cave)
     * @return Distance after subtraction (negative = inside result)
     */
    public static float subtract(float d1, float d2) {
        return Math.max(d1, -d2);
    }

    /**
     * Intersection of two SDFs (set A ∩ B).
     *
     * <p>The result is solid only where both shapes are solid. This takes the
     * maximum distance (only negative where both are negative).</p>
     *
     * <p><b>Use Cases:</b> Constraining caves to specific regions, layered geology.</p>
     *
     * @param d1 Signed distance from first primitive
     * @param d2 Signed distance from second primitive
     * @return Maximum distance (negative = inside intersection)
     */
    public static float intersect(float d1, float d2) {
        return Math.max(d1, d2);
    }

    /**
     * Smooth union of two SDFs with polynomial blending.
     *
     * <p>Creates an organic blend between shapes instead of a sharp boundary.
     * The smoothness parameter k controls the blend radius:</p>
     * <ul>
     *   <li>k = 0: Hard union (equivalent to {@link #union})</li>
     *   <li>k = 4-8: Subtle organic blending (recommended for caves)</li>
     *   <li>k = 16+: Very soft blending (for terrain features)</li>
     * </ul>
     *
     * <p><b>Performance:</b> ~10 operations (abs, max, multiply, add).</p>
     *
     * @param d1 Signed distance from first primitive
     * @param d2 Signed distance from second primitive
     * @param k Smoothness factor in blocks (typically 4-16)
     * @return Smoothly blended distance
     */
    public static float smoothUnion(float d1, float d2, float k) {
        if (k <= 0.0f) {
            return union(d1, d2); // Degenerate case: hard union
        }

        // Polynomial smooth minimum
        float h = Math.max(k - Math.abs(d1 - d2), 0.0f) / k;
        return Math.min(d1, d2) - h * h * k * 0.25f;
    }

    /**
     * Smooth subtraction with polynomial blending.
     *
     * <p>Creates natural erosion-like transitions when carving caves or arches.
     * The blend creates rounded edges instead of sharp cutouts.</p>
     *
     * <p><b>Use Cases:</b> Natural cave entrances, weathered arches, erosion effects.</p>
     *
     * @param d1 Signed distance from base shape
     * @param d2 Signed distance from subtracted shape
     * @param k Smoothness factor in blocks (typically 2-8 for caves)
     * @return Smoothly blended distance after subtraction
     */
    public static float smoothSubtract(float d1, float d2, float k) {
        if (k <= 0.0f) {
            return subtract(d1, d2); // Degenerate case: hard subtraction
        }

        // Polynomial smooth maximum (subtraction is max(d1, -d2))
        float h = Math.max(k - Math.abs(d1 + d2), 0.0f) / k;
        return Math.max(d1, -d2) + h * h * k * 0.25f;
    }

    /**
     * Smooth intersection with polynomial blending.
     *
     * <p>Creates organic transitions when constraining shapes to regions.</p>
     *
     * @param d1 Signed distance from first primitive
     * @param d2 Signed distance from second primitive
     * @param k Smoothness factor in blocks
     * @return Smoothly blended distance of intersection
     */
    public static float smoothIntersect(float d1, float d2, float k) {
        if (k <= 0.0f) {
            return intersect(d1, d2); // Degenerate case: hard intersection
        }

        // Polynomial smooth maximum
        float h = Math.max(k - Math.abs(d1 - d2), 0.0f) / k;
        return Math.max(d1, d2) + h * h * k * 0.25f;
    }

    /**
     * Exponential smooth union (alternative blending method).
     *
     * <p>Uses exponential interpolation instead of polynomial. Produces slightly
     * different aesthetics - more "blobby" organic shapes. More expensive than
     * polynomial smooth union.</p>
     *
     * <p><b>Performance:</b> ~20 operations (exp, log, multiply). Use sparingly.</p>
     *
     * @param d1 Signed distance from first primitive
     * @param d2 Signed distance from second primitive
     * @param k Smoothness factor (typically 8-32 for terrain)
     * @return Exponentially blended distance
     */
    public static float exponentialSmoothUnion(float d1, float d2, float k) {
        if (k <= 0.0f) {
            return union(d1, d2);
        }

        float res = (float) Math.exp(-k * d1) + (float) Math.exp(-k * d2);
        return (float) (-Math.log(res) / k);
    }

    /**
     * Linear interpolation between two SDF values.
     *
     * <p>Useful for blending between different terrain generation modes or
     * transitioning between biomes.</p>
     *
     * @param d1 First signed distance
     * @param d2 Second signed distance
     * @param t Interpolation factor (0 = all d1, 1 = all d2)
     * @return Linearly interpolated distance
     */
    public static float lerp(float d1, float d2, float t) {
        return d1 + t * (d2 - d1);
    }

    /**
     * Clamp a value between min and max.
     *
     * @param value Value to clamp
     * @param min Minimum value
     * @param max Maximum value
     * @return Clamped value
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
