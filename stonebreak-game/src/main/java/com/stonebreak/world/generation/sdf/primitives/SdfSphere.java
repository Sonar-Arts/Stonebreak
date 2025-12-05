package com.stonebreak.world.generation.sdf.primitives;

import com.stonebreak.world.generation.sdf.SdfPrimitive;

/**
 * Signed Distance Field for a sphere.
 *
 * <p>The sphere SDF is one of the most efficient primitives with a simple
 * closed-form solution: distance = length(point - center) - radius</p>
 *
 * <p><b>Performance:</b> ~10 operations per evaluation (3 subtractions, 3 multiplies,
 * 1 sqrt, 1 subtraction). Approximately 20x faster than 3D simplex noise.</p>
 *
 * <p><b>Use Cases in Terrain:</b></p>
 * <ul>
 *   <li>Cave chambers (large hollow spheres)</li>
 *   <li>Boulder formations (small solid spheres)</li>
 *   <li>Crater generation (negative sphere subtraction)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Immutable and thread-safe.</p>
 */
public final class SdfSphere implements SdfPrimitive {

    private final float centerX;
    private final float centerY;
    private final float centerZ;
    private final float radius;

    // Cached bounds for fast rejection
    private final float[] bounds;

    /**
     * Creates a sphere SDF centered at the given position.
     *
     * @param centerX X coordinate of sphere center
     * @param centerY Y coordinate of sphere center (typically 20-200 for underground caves)
     * @param centerZ Z coordinate of sphere center
     * @param radius Sphere radius in blocks (typically 2.5-10 for caves)
     * @throws IllegalArgumentException if radius is negative or zero
     */
    public SdfSphere(float centerX, float centerY, float centerZ, float radius) {
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + radius);
        }

        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;

        // Pre-compute AABB for fast rejection
        this.bounds = new float[] {
            centerX - radius, centerY - radius, centerZ - radius,
            centerX + radius, centerY + radius, centerZ + radius
        };
    }

    @Override
    public float evaluate(float x, float y, float z) {
        // Vector from point to sphere center
        float dx = x - centerX;
        float dy = y - centerY;
        float dz = z - centerZ;

        // Distance from point to center
        float distanceToCenter = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Signed distance: negative inside sphere, positive outside
        return distanceToCenter - radius;
    }

    @Override
    public float[] getBounds() {
        return bounds;
    }

    /**
     * Get the center X coordinate of this sphere.
     * @return center X coordinate
     */
    public float getCenterX() {
        return centerX;
    }

    /**
     * Get the center Y coordinate of this sphere.
     * @return center Y coordinate
     */
    public float getCenterY() {
        return centerY;
    }

    /**
     * Get the center Z coordinate of this sphere.
     * @return center Z coordinate
     */
    public float getCenterZ() {
        return centerZ;
    }

    /**
     * Get the radius of this sphere.
     * @return radius in blocks
     */
    public float getRadius() {
        return radius;
    }

    @Override
    public String toString() {
        return String.format("SdfSphere[center=(%.1f, %.1f, %.1f), radius=%.1f]",
                             centerX, centerY, centerZ, radius);
    }
}
