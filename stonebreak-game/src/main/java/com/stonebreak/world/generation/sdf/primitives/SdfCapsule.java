package com.stonebreak.world.generation.sdf.primitives;

import com.stonebreak.world.generation.sdf.SdfPrimitive;

/**
 * Signed Distance Field for a capsule (cylinder with hemispherical end caps).
 *
 * <p>A capsule is defined by two endpoints and a radius. It's essentially a line
 * segment with a sphere swept along it, creating a pill-like shape.</p>
 *
 * <p><b>Performance:</b> ~20 operations per evaluation (dot products, clamp, sqrt).
 * Still significantly faster than 3D noise (~200 operations).</p>
 *
 * <p><b>Use Cases in Terrain:</b></p>
 * <ul>
 *   <li>Cave tunnels (long winding passages)</li>
 *   <li>Lava tubes (volcanic caves)</li>
 *   <li>Natural corridors connecting cave chambers</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Immutable and thread-safe.</p>
 */
public final class SdfCapsule implements SdfPrimitive {

    private final float ax, ay, az; // Point A (start)
    private final float bx, by, bz; // Point B (end)
    private final float radius;

    // Cached values for optimization
    private final float bax, bay, baz; // B - A vector
    private final float baDotBa;        // dot(B-A, B-A)
    private final float[] bounds;

    /**
     * Creates a capsule SDF between two points with given radius.
     *
     * @param ax X coordinate of point A (start)
     * @param ay Y coordinate of point A
     * @param az Z coordinate of point A
     * @param bx X coordinate of point B (end)
     * @param by Y coordinate of point B
     * @param bz Z coordinate of point B
     * @param radius Capsule radius in blocks (typically 2-4 for cave tunnels)
     * @throws IllegalArgumentException if radius is negative or zero, or if A == B
     */
    public SdfCapsule(float ax, float ay, float az,
                      float bx, float by, float bz,
                      float radius) {
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Capsule radius must be positive, got: " + radius);
        }

        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.radius = radius;

        // Pre-compute B - A vector
        this.bax = bx - ax;
        this.bay = by - ay;
        this.baz = bz - az;
        this.baDotBa = bax * bax + bay * bay + baz * baz;

        if (baDotBa < 0.0001f) {
            throw new IllegalArgumentException("Capsule endpoints must be distinct (A != B)");
        }

        // Compute AABB
        float minX = Math.min(ax, bx) - radius;
        float minY = Math.min(ay, by) - radius;
        float minZ = Math.min(az, bz) - radius;
        float maxX = Math.max(ax, bx) + radius;
        float maxY = Math.max(ay, by) + radius;
        float maxZ = Math.max(az, bz) + radius;

        this.bounds = new float[] { minX, minY, minZ, maxX, maxY, maxZ };
    }

    @Override
    public float evaluate(float x, float y, float z) {
        // Vector from point A to query point
        float pax = x - ax;
        float pay = y - ay;
        float paz = z - az;

        // Project point onto line segment AB
        // h = clamp(dot(P-A, B-A) / dot(B-A, B-A), 0, 1)
        float h = (pax * bax + pay * bay + paz * baz) / baDotBa;
        h = Math.max(0.0f, Math.min(1.0f, h)); // Clamp to [0, 1]

        // Closest point on line segment
        float closestX = ax + bax * h;
        float closestY = ay + bay * h;
        float closestZ = az + baz * h;

        // Distance from query point to closest point on segment
        float dx = x - closestX;
        float dy = y - closestY;
        float dz = z - closestZ;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Signed distance: negative inside capsule, positive outside
        return distance - radius;
    }

    @Override
    public float[] getBounds() {
        return bounds;
    }

    /**
     * Get the start point A of this capsule.
     * @return array {ax, ay, az}
     */
    public float[] getPointA() {
        return new float[] { ax, ay, az };
    }

    /**
     * Get the end point B of this capsule.
     * @return array {bx, by, bz}
     */
    public float[] getPointB() {
        return new float[] { bx, by, bz };
    }

    /**
     * Get the radius of this capsule.
     * @return radius in blocks
     */
    public float getRadius() {
        return radius;
    }

    /**
     * Get the length of the capsule's central axis.
     * @return distance between points A and B in blocks
     */
    public float getLength() {
        return (float) Math.sqrt(baDotBa);
    }

    @Override
    public String toString() {
        return String.format("SdfCapsule[A=(%.1f,%.1f,%.1f), B=(%.1f,%.1f,%.1f), radius=%.1f]",
                             ax, ay, az, bx, by, bz, radius);
    }
}
