package com.openmason.main.systems.menus.textureCreator.utils;

import com.openmason.main.systems.menus.textureCreator.SymmetryState;

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
     * Calculate the mirrored copies of a line segment for a symmetry mode.
     *
     * <p>Each mirror transform of the mode is applied to <em>both</em> endpoints, so
     * a segment that starts on an axis keeps its pairing (unlike pairing the
     * per-point lists of {@link #calculateMirrorPoints}, which drop on-axis points
     * and would then match a vertical mirror with a horizontal one). The original
     * segment is not included, and copies identical to the original or to an
     * earlier copy are skipped so partial-coverage pixels are never blended twice.
     *
     * @return mirrored segments as {@code {start, end}} pairs (original excluded)
     */
    public static List<Point2i[]> calculateMirrorSegments(int x0, int y0, int x1, int y1,
                                                          SymmetryState.SymmetryMode mode,
                                                          int canvasWidth, int canvasHeight,
                                                          int offsetX, int offsetY) {
        List<Point2i[]> segments = new ArrayList<>();
        if (mode == null || mode == SymmetryState.SymmetryMode.NONE) {
            return segments;
        }

        float centerX = getAxisCenterX(canvasWidth, offsetX);
        float centerY = getAxisCenterY(canvasHeight, offsetY);
        int mx0 = Math.round(2 * centerX - x0);
        int mx1 = Math.round(2 * centerX - x1);
        int my0 = Math.round(2 * centerY - y0);
        int my1 = Math.round(2 * centerY - y1);

        Point2i[] original = {new Point2i(x0, y0), new Point2i(x1, y1)};
        switch (mode) {
            case HORIZONTAL -> addSegment(segments, original, new Point2i(x0, my0), new Point2i(x1, my1));
            case VERTICAL -> addSegment(segments, original, new Point2i(mx0, y0), new Point2i(mx1, y1));
            case QUADRANT -> {
                addSegment(segments, original, new Point2i(mx0, y0), new Point2i(mx1, y1));
                addSegment(segments, original, new Point2i(x0, my0), new Point2i(x1, my1));
                addSegment(segments, original, new Point2i(mx0, my0), new Point2i(mx1, my1));
            }
            default -> {
                // NONE handled above
            }
        }
        return segments;
    }

    private static void addSegment(List<Point2i[]> segments, Point2i[] original, Point2i start, Point2i end) {
        if (sameSegment(original, start, end)) {
            return;
        }
        for (Point2i[] existing : segments) {
            if (sameSegment(existing, start, end)) {
                return;
            }
        }
        segments.add(new Point2i[]{start, end});
    }

    private static boolean sameSegment(Point2i[] segment, Point2i start, Point2i end) {
        return (segment[0].equals(start) && segment[1].equals(end))
                || (segment[0].equals(end) && segment[1].equals(start));
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
