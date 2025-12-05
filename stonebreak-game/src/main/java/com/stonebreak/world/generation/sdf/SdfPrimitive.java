package com.stonebreak.world.generation.sdf;

/**
 * Signed Distance Field primitive interface.
 *
 * <p>A Signed Distance Field (SDF) represents a 3D surface implicitly by storing
 * the signed distance to the nearest surface at every point in space:</p>
 * <ul>
 *   <li><b>Negative values</b>: Inside the shape (below surface)</li>
 *   <li><b>Zero</b>: Exactly on the surface</li>
 *   <li><b>Positive values</b>: Outside the shape (above surface)</li>
 * </ul>
 *
 * <p><b>Performance Benefits:</b></p>
 * <p>Analytical SDF primitives (sphere, box, capsule) use closed-form mathematical
 * solutions that are 10-100x faster than 3D noise sampling. For example:</p>
 * <ul>
 *   <li>Sphere SDF: ~10 operations (sqrt + subtraction)</li>
 *   <li>3D Simplex Noise: ~200 operations (gradient table lookups, interpolation)</li>
 * </ul>
 *
 * <p><b>Composability:</b></p>
 * <p>SDFs can be combined using Constructive Solid Geometry (CSG) operations
 * to create complex shapes from simple primitives. See {@link SdfBlendOperations}.</p>
 *
 * <p><b>Thread Safety:</b></p>
 * <p>Implementations should be immutable and thread-safe for concurrent chunk generation.</p>
 *
 * @see SdfBlendOperations
 * @see com.stonebreak.world.generation.sdf.primitives
 */
public interface SdfPrimitive {

    /**
     * Evaluate signed distance at given world position.
     *
     * <p>Returns the shortest distance to the surface of this primitive:</p>
     * <ul>
     *   <li>Negative: point is inside the primitive (solid terrain)</li>
     *   <li>Zero: point is exactly on the surface boundary</li>
     *   <li>Positive: point is outside the primitive (air/cave space)</li>
     * </ul>
     *
     * <p><b>Performance Critical:</b> This method is called thousands of times
     * per chunk. Implementations must be highly optimized.</p>
     *
     * @param x World X coordinate
     * @param y World Y coordinate (0-256)
     * @param z World Z coordinate
     * @return Signed distance to nearest surface (in blocks)
     */
    float evaluate(float x, float y, float z);

    /**
     * Get axis-aligned bounding box for spatial culling.
     *
     * <p>Returns AABB in format: {minX, minY, minZ, maxX, maxY, maxZ}</p>
     *
     * <p>Used by spatial hash grid to efficiently reject primitives that
     * cannot possibly affect a given query point. This optimization can
     * reduce evaluation costs by 90%+ in sparse regions.</p>
     *
     * <p><b>Note:</b> Bounds should be conservative (slightly larger is okay,
     * but never smaller than the actual primitive).</p>
     *
     * @return 6-element array: {minX, minY, minZ, maxX, maxY, maxZ}
     */
    float[] getBounds();

    /**
     * Fast bounds check without full SDF evaluation.
     *
     * <p>Tests if a point is definitely outside the bounding box.
     * Used as a fast rejection test before expensive SDF evaluation.</p>
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return true if point is definitely outside bounds (can skip evaluation)
     */
    default boolean isOutsideBounds(float x, float y, float z) {
        float[] bounds = getBounds();
        return x < bounds[0] || x > bounds[3] ||
               y < bounds[1] || y > bounds[4] ||
               z < bounds[2] || z > bounds[5];
    }
}
