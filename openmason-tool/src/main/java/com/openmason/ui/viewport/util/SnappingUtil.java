package com.openmason.ui.viewport.util;

/**
 * Utility class for grid snapping operations.
 * <p>
 * Provides simple, stateless methods for snapping values to grid increments.
 * Follows KISS principle with minimal, focused functionality.
 * </p>
 *
 * <p><b>Block Size Standard:</b>
 * All grid snapping increments are based on the standard block size of <b>1.0 world unit</b>.
 * This matches the block size used in Stonebreak and .OMO model files.
 * <ul>
 *   <li>1.0 unit = 1 standard block (cube spanning -0.5 to +0.5 on each axis when centered)</li>
 *   <li>Visual grid lines are rendered at 1.0 unit intervals</li>
 *   <li>Recommended snap increments: 1.0 (full), 0.5 (half), 0.25 (quarter), 0.125 (eighth)</li>
 * </ul>
 * </p>
 */
public class SnappingUtil {

    /**
     * Standard block size in world units.
     * This is the canonical size of a block/cube in both Stonebreak and Open Mason.
     * <p>
     * A standard block is a 1.0×1.0×1.0 cube in world space.
     * When centered at origin, it spans from -0.5 to +0.5 on each axis.
     * </p>
     */
    public static final float STANDARD_BLOCK_SIZE = 1.0f;

    /**
     * Recommended snap increment: Full block (1.0 unit).
     * One snap position per visual grid square.
     */
    public static final float SNAP_FULL_BLOCK = STANDARD_BLOCK_SIZE;

    /**
     * Recommended snap increment: Half block (0.5 units).
     * Two snap positions per visual grid square. Good default for most work.
     */
    public static final float SNAP_HALF_BLOCK = STANDARD_BLOCK_SIZE / 2.0f;

    /**
     * Recommended snap increment: Quarter block (0.25 units).
     * Four snap positions per visual grid square. Good for detailed work.
     */
    public static final float SNAP_QUARTER_BLOCK = STANDARD_BLOCK_SIZE / 4.0f;

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private SnappingUtil() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Snap a value to the nearest grid increment.
     * <p>
     * If increment is zero or negative, returns the original value unchanged.
     * Otherwise, rounds the value to the nearest multiple of the increment.
     * </p>
     *
     * @param value     the value to snap
     * @param increment the grid increment (must be positive)
     * @return the snapped value, or original value if increment is invalid
     */
    public static float snapToGrid(float value, float increment) {
        if (increment <= 0) {
            return value;
        }
        return Math.round(value / increment) * increment;
    }

    /**
     * Snap a 3D position to the nearest grid increments.
     * <p>
     * Convenience method that applies snapping to all three components (x, y, z).
     * Modifies the provided array in-place.
     * </p>
     *
     * @param position  the position array [x, y, z] (modified in-place)
     * @param increment the grid increment
     */
    public static void snapPosition(float[] position, float increment) {
        if (position == null || position.length < 3) {
            return;
        }
        position[0] = snapToGrid(position[0], increment);
        position[1] = snapToGrid(position[1], increment);
        position[2] = snapToGrid(position[2], increment);
    }
}
