package com.stonebreak.world.generation.sdf.primitives;

import com.stonebreak.world.generation.sdf.SdfPrimitive;

/**
 * Signed Distance Field for a capped cylinder (vertical orientation).
 *
 * <p>This cylinder is oriented along the Y-axis (vertical) which is ideal for
 * terrain generation features like vertical cave shafts, tree trunks, or pillars.</p>
 *
 * <p><b>Performance:</b> ~12 operations per evaluation (2D distance + cap clamp).
 * Faster than 3D noise and perfect for vertical natural structures.</p>
 *
 * <p><b>Use Cases in Terrain:</b></p>
 * <ul>
 *   <li>Vertical cave shafts (natural chimneys)</li>
 *   <li>Stone pillars in large caverns</li>
 *   <li>Natural arch supports (cylinder subtraction)</li>
 *   <li>Tree trunk placeholders</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Immutable and thread-safe.</p>
 */
public final class SdfCylinder implements SdfPrimitive {

    private final float centerX, centerZ; // Center in XZ plane
    private final float centerY;          // Y center
    private final float radius;           // Cylinder radius
    private final float halfHeight;       // Half-height (from center to top/bottom)
    private final float[] bounds;

    /**
     * Creates a vertical cylinder SDF.
     *
     * @param centerX X coordinate of cylinder axis center
     * @param centerY Y coordinate of cylinder center (midpoint between top and bottom)
     * @param centerZ Z coordinate of cylinder axis center
     * @param radius Cylinder radius in XZ plane (blocks)
     * @param halfHeight Half-height along Y axis (centerY ± halfHeight = top/bottom)
     * @throws IllegalArgumentException if radius or halfHeight is negative or zero
     */
    public SdfCylinder(float centerX, float centerY, float centerZ,
                       float radius, float halfHeight) {
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Cylinder radius must be positive, got: " + radius);
        }
        if (halfHeight <= 0.0f) {
            throw new IllegalArgumentException("Cylinder halfHeight must be positive, got: " + halfHeight);
        }

        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;
        this.halfHeight = halfHeight;

        // Compute AABB
        this.bounds = new float[] {
            centerX - radius, centerY - halfHeight, centerZ - radius,
            centerX + radius, centerY + halfHeight, centerZ + radius
        };
    }

    /**
     * Convenience constructor using total height instead of half-height.
     *
     * @param centerX X coordinate of cylinder axis center
     * @param centerY Y coordinate of cylinder center
     * @param centerZ Z coordinate of cylinder axis center
     * @param radius Cylinder radius
     * @param totalHeight Total height (will be divided by 2 for halfHeight)
     * @param useTotal Dummy parameter to differentiate constructor (pass true)
     */
    public static SdfCylinder fromTotalHeight(float centerX, float centerY, float centerZ,
                                               float radius, float totalHeight) {
        return new SdfCylinder(centerX, centerY, centerZ, radius, totalHeight / 2.0f);
    }

    @Override
    public float evaluate(float x, float y, float z) {
        // Distance in XZ plane (horizontal distance from cylinder axis)
        float dx = x - centerX;
        float dz = z - centerZ;
        float horizontalDistance = (float) Math.sqrt(dx * dx + dz * dz);

        // Distance along Y axis (vertical distance from center)
        float dy = y - centerY;

        // 2D SDF in (r, y) space where r = horizontal distance
        float qr = horizontalDistance - radius;
        float qy = Math.abs(dy) - halfHeight;

        // Similar to box SDF, but in cylindrical coordinates
        float outsideR = Math.max(qr, 0.0f);
        float outsideY = Math.max(qy, 0.0f);
        float outsideDistance = (float) Math.sqrt(outsideR * outsideR + outsideY * outsideY);

        float insideDistance = Math.max(qr, qy);

        return outsideDistance + Math.min(insideDistance, 0.0f);
    }

    @Override
    public float[] getBounds() {
        return bounds;
    }

    /**
     * Get the center position of this cylinder.
     * @return array {centerX, centerY, centerZ}
     */
    public float[] getCenter() {
        return new float[] { centerX, centerY, centerZ };
    }

    /**
     * Get the radius of this cylinder.
     * @return radius in blocks
     */
    public float getRadius() {
        return radius;
    }

    /**
     * Get the half-height of this cylinder.
     * @return half-height in blocks
     */
    public float getHalfHeight() {
        return halfHeight;
    }

    /**
     * Get the total height of this cylinder.
     * @return total height (2 × halfHeight) in blocks
     */
    public float getTotalHeight() {
        return halfHeight * 2.0f;
    }

    @Override
    public String toString() {
        return String.format("SdfCylinder[center=(%.1f,%.1f,%.1f), radius=%.1f, height=%.1f]",
                             centerX, centerY, centerZ, radius, getTotalHeight());
    }
}
