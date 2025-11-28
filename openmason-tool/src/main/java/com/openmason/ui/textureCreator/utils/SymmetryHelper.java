package com.openmason.ui.textureCreator.utils;

import com.openmason.ui.textureCreator.SymmetryState;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for calculating mirrored coordinates for symmetry/mirror mode.
 */
public class SymmetryHelper {

    /**
         * Simple 2D integer point structure.
         */
        public record Point2i(int x, int y) {

        @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof Point2i other)) return false;
            return x == other.x && y == other.y;
            }

        @Override
            public String toString() {
                return "(" + x + ", " + y + ")";
            }
        }

    /**
     * Calculate all mirrored points for a given coordinate based on symmetry mode.
     * Returns all points including the original point that should be drawn.
     */
    public static List<Point2i> calculateMirrorPoints(int x, int y,
                                                      SymmetryState.SymmetryMode mode,
                                                      int canvasWidth, int canvasHeight,
                                                      int offsetX, int offsetY) {
        List<Point2i> points = new ArrayList<>();

        // Always include the original point
        points.add(new Point2i(x, y));

        // If no symmetry, return just the original point
        if (mode == SymmetryState.SymmetryMode.NONE) {
            return points;
        }

        // Calculate center with offset using the same method as rendering
        // This ensures axis visualization and actual mirroring are always aligned
        float centerX = getAxisCenterX(canvasWidth, offsetX);
        float centerY = getAxisCenterY(canvasHeight, offsetY);

        switch (mode) {
            case HORIZONTAL:
                // Mirror across horizontal axis (top/bottom)
                int mirrorY = Math.round(2 * centerY - y);
                if (mirrorY != y) { // Avoid duplicate if on axis
                    points.add(new Point2i(x, mirrorY));
                }
                break;

            case VERTICAL:
                // Mirror across vertical axis (left/right)
                int mirrorX = Math.round(2 * centerX - x);
                if (mirrorX != x) { // Avoid duplicate if on axis
                    points.add(new Point2i(mirrorX, y));
                }
                break;

            case QUADRANT:
                // Mirror in all four quadrants
                int mirrorXQ = Math.round(2 * centerX - x);
                int mirrorYQ = Math.round(2 * centerY - y);

                // Add horizontally mirrored point
                if (mirrorXQ != x) {
                    points.add(new Point2i(mirrorXQ, y));
                }

                // Add vertically mirrored point
                if (mirrorYQ != y) {
                    points.add(new Point2i(x, mirrorYQ));
                }

                // Add diagonally mirrored point (both axes)
                if (mirrorXQ != x && mirrorYQ != y) {
                    points.add(new Point2i(mirrorXQ, mirrorYQ));
                }
                break;

            default:
                // Already handled above
                break;
        }

        return points;
    }

    /**
     * Get the axis center X coordinate with offset.
     */
    public static float getAxisCenterX(int canvasWidth, int offsetX) {
        return ((canvasWidth - 1) / 2.0f) + offsetX;
    }

    /**
     * Get the axis center Y coordinate with offset.
     */
    public static float getAxisCenterY(int canvasHeight, int offsetY) {
        return ((canvasHeight - 1) / 2.0f) + offsetY;
    }
}
