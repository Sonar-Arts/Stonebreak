package com.stonebreak.world.generation.utils;

/**
 * Bilinear interpolation utilities for grid-based water systems.
 *
 * <p>Eliminates 100% duplicate code between WaterLevelGrid and BasinWaterLevelGrid.
 * Both classes had identical 21-line {@code bilinearInterpolate()} methods.</p>
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Handles mixed water/no-water boundaries using nearest-neighbor</li>
 *   <li>Uses minimum value for all-water regions (ensures flat lake surfaces)</li>
 *   <li>Preserves sharp edges at basin boundaries</li>
 * </ul>
 *
 * @since Terra v.12 (Refactoring)
 */
public final class GridInterpolation {

    private GridInterpolation() {
        // Utility class - prevent instantiation
    }

    /**
     * Bilinear interpolation for water level grids.
     *
     * <p>Interpolates water level from four grid corners. Handles three cases:</p>
     * <ol>
     *   <li><b>No water:</b> All corners are -1 → return -1</li>
     *   <li><b>Mixed boundaries:</b> Some corners are -1 → use nearest-neighbor (preserves sharp edges)</li>
     *   <li><b>All water:</b> All corners valid → minimum value for flat surfaces</li>
     * </ol>
     *
     * <p><b>Grid layout:</b></p>
     * <pre>
     *   v01 ---- v11
     *    |        |
     *    |   (tx, tz)
     *    |        |
     *   v00 ---- v10
     * </pre>
     *
     * <p><b>Minimum value formula (all-water case):</b></p>
     * <pre>
     * result = min(v00, v10, v01, v11)  (use minimum rim height for flat surfaces)
     * </pre>
     * <p>This ensures lakes have flat water surfaces at a single Y level,
     * preventing interpolation artifacts that create stepped/wavy surfaces.</p>
     *
     * <p><b>Used by:</b> WaterLevelGrid, BasinWaterLevelGrid</p>
     *
     * @param v00 Grid value at (gridX, gridZ) - bottom-left corner
     * @param v10 Grid value at (gridX+1, gridZ) - bottom-right corner
     * @param v01 Grid value at (gridX, gridZ+1) - top-left corner
     * @param v11 Grid value at (gridX+1, gridZ+1) - top-right corner
     * @param tx Local X position [0.0-1.0] within grid cell
     * @param tz Local Z position [0.0-1.0] within grid cell
     * @return Interpolated water level, or -1 if no water
     */
    public static int bilinearInterpolate(int v00, int v10, int v01, int v11, float tx, float tz) {
        // Case 1: If all corners are -1 (no water), return -1
        boolean hasWater = (v00 != -1 || v10 != -1 || v01 != -1 || v11 != -1);
        if (!hasWater) {
            return -1;
        }

        // Case 2: If mixed water/no-water, use nearest neighbor (preserves sharp edges at basin boundaries)
        if (v00 == -1 || v10 == -1 || v01 == -1 || v11 == -1) {
            // Use nearest corner based on position
            // IMPORTANT: Use <= for bottom-left to handle boundary cases (prefer v00 at grid points)
            if (tx <= 0.5f && tz <= 0.5f) return v00;  // Closest to bottom-left
            if (tx > 0.5f && tz <= 0.5f) return v10;   // Closest to bottom-right
            if (tx <= 0.5f && tz > 0.5f) return v01;   // Closest to top-left
            return v11;                                 // Closest to top-right
        }

        // Case 3: Use minimum water level for all-water regions (ensures flat lake surfaces)
        // The minimum rim height is the correct water level (lowest spillover point)
        // This prevents interpolation artifacts that create stepped/wavy surfaces
        return Math.min(Math.min(v00, v10), Math.min(v01, v11));
    }
}
