package com.stonebreak.world.generation.sdf.primitives;

import com.stonebreak.world.generation.sdf.SdfPrimitive;

/**
 * Signed Distance Field for an axis-aligned box (rectangular prism).
 *
 * <p>Box SDF uses a clever distance formula that handles both interior and
 * exterior points efficiently. The algorithm computes distance to the nearest
 * face, edge, or corner depending on the query point's position.</p>
 *
 * <p><b>Performance:</b> ~15 operations per evaluation (6 max ops, length calculation).
 * Much faster than 3D noise and useful for architectural cave features.</p>
 *
 * <p><b>Use Cases in Terrain:</b></p>
 * <ul>
 *   <li>Rectangular cave chambers (mine-like structures)</li>
 *   <li>Cliff faces (vertical box subtraction)</li>
 *   <li>Stratified rock layers (horizontal box intersection)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Immutable and thread-safe.</p>
 */
public final class SdfBox implements SdfPrimitive {

    private final float centerX, centerY, centerZ;
    private final float halfExtentX, halfExtentY, halfExtentZ;
    private final float[] bounds;

    /**
     * Creates a box SDF centered at the given position with specified half-extents.
     *
     * <p>Half-extents are the distances from center to each face. For example,
     * a box with halfExtentX=5 extends from centerX-5 to centerX+5 (width=10).</p>
     *
     * @param centerX X coordinate of box center
     * @param centerY Y coordinate of box center
     * @param centerZ Z coordinate of box center
     * @param halfExtentX Half-width (X dimension) in blocks
     * @param halfExtentY Half-height (Y dimension) in blocks
     * @param halfExtentZ Half-depth (Z dimension) in blocks
     * @throws IllegalArgumentException if any half-extent is negative or zero
     */
    public SdfBox(float centerX, float centerY, float centerZ,
                  float halfExtentX, float halfExtentY, float halfExtentZ) {
        if (halfExtentX <= 0 || halfExtentY <= 0 || halfExtentZ <= 0) {
            throw new IllegalArgumentException(
                String.format("Box half-extents must be positive, got: (%.2f, %.2f, %.2f)",
                             halfExtentX, halfExtentY, halfExtentZ));
        }

        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.halfExtentX = halfExtentX;
        this.halfExtentY = halfExtentY;
        this.halfExtentZ = halfExtentZ;

        // AABB is simply the box itself
        this.bounds = new float[] {
            centerX - halfExtentX, centerY - halfExtentY, centerZ - halfExtentZ,
            centerX + halfExtentX, centerY + halfExtentY, centerZ + halfExtentZ
        };
    }

    /**
     * Convenience constructor for a cube (all dimensions equal).
     *
     * @param centerX X coordinate of cube center
     * @param centerY Y coordinate of cube center
     * @param centerZ Z coordinate of cube center
     * @param halfExtent Half-extent for all three dimensions
     */
    public SdfBox(float centerX, float centerY, float centerZ, float halfExtent) {
        this(centerX, centerY, centerZ, halfExtent, halfExtent, halfExtent);
    }

    @Override
    public float evaluate(float x, float y, float z) {
        // Translate to box-local coordinates (centered at origin)
        float localX = x - centerX;
        float localY = y - centerY;
        float localZ = z - centerZ;

        // Distance from point to box (using absolute value for symmetry)
        float qx = Math.abs(localX) - halfExtentX;
        float qy = Math.abs(localY) - halfExtentY;
        float qz = Math.abs(localZ) - halfExtentZ;

        // Distance to box:
        // - If all q components are negative, point is inside: return max(qx, qy, qz)
        // - If any q component is positive, point is outside: compute Euclidean distance

        // Outside distance (only positive components contribute)
        float outsideX = Math.max(qx, 0.0f);
        float outsideY = Math.max(qy, 0.0f);
        float outsideZ = Math.max(qz, 0.0f);
        float outsideDistance = (float) Math.sqrt(
            outsideX * outsideX + outsideY * outsideY + outsideZ * outsideZ
        );

        // Inside distance (max of negative components)
        float insideDistance = Math.max(Math.max(qx, qy), qz);

        // Combine: outside distance is positive, inside distance is negative
        return outsideDistance + Math.min(insideDistance, 0.0f);
    }

    @Override
    public float[] getBounds() {
        return bounds;
    }

    /**
     * Get the center position of this box.
     * @return array {centerX, centerY, centerZ}
     */
    public float[] getCenter() {
        return new float[] { centerX, centerY, centerZ };
    }

    /**
     * Get the half-extents of this box.
     * @return array {halfExtentX, halfExtentY, halfExtentZ}
     */
    public float[] getHalfExtents() {
        return new float[] { halfExtentX, halfExtentY, halfExtentZ };
    }

    /**
     * Get the full dimensions of this box.
     * @return array {width, height, depth} (each is 2× the corresponding half-extent)
     */
    public float[] getDimensions() {
        return new float[] {
            halfExtentX * 2,
            halfExtentY * 2,
            halfExtentZ * 2
        };
    }

    @Override
    public String toString() {
        return String.format("SdfBox[center=(%.1f,%.1f,%.1f), halfExtents=(%.1f,%.1f,%.1f)]",
                             centerX, centerY, centerZ,
                             halfExtentX, halfExtentY, halfExtentZ);
    }
}
