package com.openmason.ui.components.textureCreator.tools.moveTool.transform;

/**
 * Utility class for geometric calculations, primarily rotation operations.
 * Follows KISS and YAGNI principles - only essential geometry operations.
 * All methods are static and stateless.
 *
 * @author Open Mason Team
 */
public class GeometryHelper {

    private GeometryHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Rotates a point around a center by the given angle.
     *
     * @param x Point x coordinate
     * @param y Point y coordinate
     * @param centerX Center x coordinate
     * @param centerY Center y coordinate
     * @param angleDegrees Rotation angle in degrees
     * @return Array [rotatedX, rotatedY]
     */
    public static double[] rotatePoint(double x, double y, double centerX, double centerY, double angleDegrees) {
        double angleRad = Math.toRadians(angleDegrees);
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);

        double dx = x - centerX;
        double dy = y - centerY;

        double rotatedX = centerX + dx * cos - dy * sin;
        double rotatedY = centerY + dx * sin + dy * cos;

        return new double[]{rotatedX, rotatedY};
    }

    /**
     * Calculates the 4 rotated corner positions of a rectangular region.
     *
     * @param x1 Left bound
     * @param y1 Top bound
     * @param x2 Right bound
     * @param y2 Bottom bound
     * @param angleDegrees Rotation angle in degrees
     * @return Array of 8 values: [x1,y1, x2,y1, x2,y2, x1,y2] representing 4 rotated corners
     */
    public static double[] calculateRotatedCorners(int x1, int y1, int x2, int y2, double angleDegrees) {
        // Calculate center using visual box coordinates
        double boxRight = x2 + 1;
        double boxBottom = y2 + 1;
        double centerX = (x1 + boxRight) / 2.0;
        double centerY = (y1 + boxBottom) / 2.0;

        // Rotate all 4 corners of the visual box
        double[] topLeft = rotatePoint(x1, y1, centerX, centerY, angleDegrees);
        double[] topRight = rotatePoint(boxRight, y1, centerX, centerY, angleDegrees);
        double[] bottomRight = rotatePoint(boxRight, boxBottom, centerX, centerY, angleDegrees);
        double[] bottomLeft = rotatePoint(x1, boxBottom, centerX, centerY, angleDegrees);

        return new double[]{
            topLeft[0], topLeft[1],
            topRight[0], topRight[1],
            bottomRight[0], bottomRight[1],
            bottomLeft[0], bottomLeft[1]
        };
    }

    /**
     * Calculates Euclidean distance between two points.
     *
     * @param x1 First point x
     * @param y1 First point y
     * @param x2 Second point x
     * @param y2 Second point y
     * @return Distance between the points
     */
    public static double calculateDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Calculates the center point of a rectangular region using visual box coordinates.
     *
     * @param x1 Left bound
     * @param y1 Top bound
     * @param x2 Right bound
     * @param y2 Bottom bound
     * @return Array [centerX, centerY]
     */
    public static double[] calculateCenter(int x1, int y1, int x2, int y2) {
        double boxRight = x2 + 1;
        double boxBottom = y2 + 1;
        double centerX = (x1 + boxRight) / 2.0;
        double centerY = (y1 + boxBottom) / 2.0;
        return new double[]{centerX, centerY};
    }
}
