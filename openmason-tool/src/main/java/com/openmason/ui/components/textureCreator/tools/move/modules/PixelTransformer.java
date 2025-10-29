package com.openmason.ui.components.textureCreator.tools.move.modules;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles pixel extraction, transformation, and application.
 * Uses nearest-neighbor interpolation for pixel-perfect transformations.
 */
public class PixelTransformer {

    /**
     * Applies transformation to extracted pixels with interpolation.
     * Returns a new map with transformed coordinates.
     * Uses bilinear interpolation when scaling to create smooth intermediate pixels.
     *
     * @param originalPixels Extracted pixels (relative coordinates)
     * @param transform The transformation to apply
     * @param originalBounds The original selection bounds
     * @return Map of absolute canvas coordinates to pixel colors
     */
    public Map<Point, Integer> applyTransform(Map<Point, Integer> originalPixels,
                                              TransformState transform,
                                              Rectangle originalBounds) {
        Map<Point, Integer> transformedPixels = new HashMap<>();

        if (originalPixels.isEmpty()) {
            return transformedPixels;
        }

        // Check if we need interpolation (scaling or rotation)
        boolean needsInterpolation = transform.getScaleX() != 1.0 ||
                                      transform.getScaleY() != 1.0 ||
                                      transform.getRotationDegrees() != 0;

        if (needsInterpolation) {
            // Use reverse mapping with interpolation for better quality
            transformedPixels = applyTransformWithInterpolation(originalPixels, transform, originalBounds);
        } else {
            // Use simple forward mapping for translation/rotation only
            transformedPixels = applyTransformSimple(originalPixels, transform, originalBounds);
        }

        return transformedPixels;
    }

    /**
     * Simple forward-mapping transformation (for translation/rotation without scaling).
     */
    private Map<Point, Integer> applyTransformSimple(Map<Point, Integer> originalPixels,
                                                      TransformState transform,
                                                      Rectangle originalBounds) {
        Map<Point, Integer> transformedPixels = new HashMap<>();

        // Get pivot point from transform (convert from absolute to relative coordinates)
        Point absolutePivot = transform.getPivot();
        int pivotX = absolutePivot.x - originalBounds.x;
        int pivotY = absolutePivot.y - originalBounds.y;

        // Apply transformation to each pixel
        for (Map.Entry<Point, Integer> entry : originalPixels.entrySet()) {
            Point relativePoint = entry.getKey();
            int color = entry.getValue();

            // Transform the point
            Point transformedPoint = transformPoint(
                    relativePoint.x, relativePoint.y,
                    pivotX, pivotY,
                    transform
            );

            // Convert back to absolute canvas coordinates
            Point absolutePoint = new Point(
                    originalBounds.x + transformedPoint.x,
                    originalBounds.y + transformedPoint.y
            );

            transformedPixels.put(absolutePoint, color);
        }

        return transformedPixels;
    }

    /**
     * Reverse-mapping transformation with bilinear interpolation.
     * For each destination pixel, find source coordinate and interpolate.
     */
    private Map<Point, Integer> applyTransformWithInterpolation(Map<Point, Integer> originalPixels,
                                                                 TransformState transform,
                                                                 Rectangle originalBounds) {
        Map<Point, Integer> transformedPixels = new HashMap<>();

        // Get pivot point from transform (convert from absolute to relative coordinates)
        Point absolutePivot = transform.getPivot();
        int pivotX = absolutePivot.x - originalBounds.x;
        int pivotY = absolutePivot.y - originalBounds.y;

        // Different logic for scaling-only vs rotation
        if (transform.getRotationDegrees() == 0) {
            // SCALING ONLY: Use edge-to-edge mapping for perfect fill
            int scaledWidth = (int) Math.round(originalBounds.width * Math.abs(transform.getScaleX()));
            int scaledHeight = (int) Math.round(originalBounds.height * Math.abs(transform.getScaleY()));

            // Calculate where the scaled region should start based on the pivot point
            // Transform the top-left corner (0,0) to find the new position
            Point transformedTopLeft = transformPoint(0, 0, pivotX, pivotY, transform);
            int scaledStartX = originalBounds.x + transformedTopLeft.x;
            int scaledStartY = originalBounds.y + transformedTopLeft.y;

            for (int destY = 0; destY < scaledHeight; destY++) {
                for (int destX = 0; destX < scaledWidth; destX++) {
                    // Linear mapping ensures edge-to-edge: dest [0, scaled-1] → source [0, original-1]
                    double sourceX = (scaledWidth == 1) ? (originalBounds.width - 1) / 2.0
                                                         : destX * (originalBounds.width - 1) / (double) (scaledWidth - 1);
                    double sourceY = (scaledHeight == 1) ? (originalBounds.height - 1) / 2.0
                                                          : destY * (originalBounds.height - 1) / (double) (scaledHeight - 1);

                    int color = sampleBilinear(originalPixels, sourceX, sourceY, originalBounds.width, originalBounds.height);

                    if ((color & 0xFF000000) != 0) {
                        // Use the pivot-adjusted start position
                        Point absolutePoint = new Point(scaledStartX + destX, scaledStartY + destY);
                        transformedPixels.put(absolutePoint, color);
                    }
                }
            }
        } else {
            // ROTATION: Use inverse transform (bounding box approach)
            Point[] corners = new Point[] {
                new Point(0, 0),
                new Point(originalBounds.width - 1, 0),
                new Point(0, originalBounds.height - 1),
                new Point(originalBounds.width - 1, originalBounds.height - 1)
            };

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

            for (Point corner : corners) {
                Point transformed = transformPoint(corner.x, corner.y, pivotX, pivotY, transform);
                minX = Math.min(minX, transformed.x);
                minY = Math.min(minY, transformed.y);
                maxX = Math.max(maxX, transformed.x);
                maxY = Math.max(maxY, transformed.y);
            }

            for (int destY = minY; destY <= maxY; destY++) {
                for (int destX = minX; destX <= maxX; destX++) {
                    SourceCoordinate sourceCoord = inverseTransformPointFractional(destX, destY, pivotX, pivotY, transform);

                    // Allow margin for bilinear sampling at edges
                    if (sourceCoord.x >= -0.5 && sourceCoord.x <= originalBounds.width - 0.5 &&
                        sourceCoord.y >= -0.5 && sourceCoord.y <= originalBounds.height - 0.5) {

                        int color = sampleBilinear(originalPixels, sourceCoord.x, sourceCoord.y,
                                                   originalBounds.width, originalBounds.height);

                        if ((color & 0xFF000000) != 0) {
                            Point absolutePoint = new Point(originalBounds.x + destX, originalBounds.y + destY);
                            transformedPixels.put(absolutePoint, color);
                        }
                    }
                }
            }
        }

        return transformedPixels;
    }

    /**
     * Inverse transform: given a destination point, find the source point (with fractional coordinates).
     * Returns a SourceCoordinate object containing fractional x and y values.
     */
    private SourceCoordinate inverseTransformPointFractional(int dx, int dy, int pivotX, int pivotY, TransformState transform) {
        // Start with destination coordinates
        double sx = dx;
        double sy = dy;

        // 1. Reverse translation offset
        sx -= transform.getTranslateX();
        sy -= transform.getTranslateY();

        // 2. Translate to origin (pivot becomes 0,0)
        sx -= pivotX;
        sy -= pivotY;

        // 3. Reverse rotation
        if (transform.getRotationDegrees() != 0) {
            double angleRadians = -Math.toRadians(transform.getRotationDegrees()); // Negative for inverse
            double cos = Math.cos(angleRadians);
            double sin = Math.sin(angleRadians);

            double rotatedX = sx * cos - sy * sin;
            double rotatedY = sx * sin + sy * cos;

            sx = rotatedX;
            sy = rotatedY;
        }

        // 4. Reverse scale
        if (transform.getScaleX() != 0) sx /= transform.getScaleX();
        if (transform.getScaleY() != 0) sy /= transform.getScaleY();

        // 5. Translate back from origin
        sx += pivotX;
        sy += pivotY;

        return new SourceCoordinate(sx, sy);
    }

    /**
     * Helper class to store fractional coordinates.
     */
    private static class SourceCoordinate {
        final double x, y;
        SourceCoordinate(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Sample color using bilinear interpolation with edge clamping.
     * Interpolates between 4 surrounding pixels for smooth color and opacity blending.
     * Edge pixels are clamped (repeated) so stretched images extend fully.
     *
     * @param pixels Source pixels (relative coordinates)
     * @param fx Fractional x coordinate
     * @param fy Fractional y coordinate
     * @param width Maximum width for clamping
     * @param height Maximum height for clamping
     * @return Interpolated color
     */
    private int sampleBilinear(Map<Point, Integer> pixels, double fx, double fy, int width, int height) {
        // Get integer and fractional parts
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int x1 = x0 + 1;
        int y1 = y0 + 1;

        double dx = fx - x0;
        double dy = fy - y0;

        // Clamp all coordinates to valid bounds [0, width-1] and [0, height-1]
        // This makes edge pixels repeat, ensuring stretched images extend fully
        x0 = Math.max(0, Math.min(width - 1, x0));
        x1 = Math.max(0, Math.min(width - 1, x1));
        y0 = Math.max(0, Math.min(height - 1, y0));
        y1 = Math.max(0, Math.min(height - 1, y1));

        // Get the four surrounding pixels
        Point p00 = new Point(x0, y0);
        Point p10 = new Point(x1, y0);
        Point p01 = new Point(x0, y1);
        Point p11 = new Point(x1, y1);

        int c00 = pixels.getOrDefault(p00, 0x00000000);
        int c10 = pixels.getOrDefault(p10, 0x00000000);
        int c01 = pixels.getOrDefault(p01, 0x00000000);
        int c11 = pixels.getOrDefault(p11, 0x00000000);

        // Interpolate
        return interpolateColors(c00, c10, c01, c11, dx, dy);
    }

    /**
     * Bilinear interpolation of four colors.
     * PixelCanvas format: 0xAABBGGRR (alpha, blue, green, red)
     */
    private int interpolateColors(int c00, int c10, int c01, int c11, double dx, double dy) {
        // Extract ABGR components (format: 0xAABBGGRR)
        int a00 = (c00 >> 24) & 0xFF, b00 = (c00 >> 16) & 0xFF, g00 = (c00 >> 8) & 0xFF, r00 = c00 & 0xFF;
        int a10 = (c10 >> 24) & 0xFF, b10 = (c10 >> 16) & 0xFF, g10 = (c10 >> 8) & 0xFF, r10 = c10 & 0xFF;
        int a01 = (c01 >> 24) & 0xFF, b01 = (c01 >> 16) & 0xFF, g01 = (c01 >> 8) & 0xFF, r01 = c01 & 0xFF;
        int a11 = (c11 >> 24) & 0xFF, b11 = (c11 >> 16) & 0xFF, g11 = (c11 >> 8) & 0xFF, r11 = c11 & 0xFF;

        // Interpolate each component
        int a = (int) ((1 - dx) * (1 - dy) * a00 + dx * (1 - dy) * a10 + (1 - dx) * dy * a01 + dx * dy * a11);
        int b = (int) ((1 - dx) * (1 - dy) * b00 + dx * (1 - dy) * b10 + (1 - dx) * dy * b01 + dx * dy * b11);
        int g = (int) ((1 - dx) * (1 - dy) * g00 + dx * (1 - dy) * g10 + (1 - dx) * dy * g01 + dx * dy * g11);
        int r = (int) ((1 - dx) * (1 - dy) * r00 + dx * (1 - dy) * r10 + (1 - dx) * dy * r01 + dx * dy * r11);

        // Clamp values
        a = Math.max(0, Math.min(255, a));
        b = Math.max(0, Math.min(255, b));
        g = Math.max(0, Math.min(255, g));
        r = Math.max(0, Math.min(255, r));

        // Pack back to ABGR format: 0xAABBGGRR
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Transforms a point using the given transformation state.
     * Order: Translate → Scale → Rotate (around pivot)
     *
     * @param x X coordinate (relative to selection)
     * @param y Y coordinate (relative to selection)
     * @param pivotX Pivot X (center of selection)
     * @param pivotY Pivot Y (center of selection)
     * @param transform Transformation state
     * @return Transformed point (relative coordinates)
     */
    private Point transformPoint(int x, int y, int pivotX, int pivotY, TransformState transform) {
        // Start with original coordinates
        double tx = x;
        double ty = y;

        // 1. Translate to origin (pivot point becomes 0,0)
        tx -= pivotX;
        ty -= pivotY;

        // 2. Apply scale
        tx *= transform.getScaleX();
        ty *= transform.getScaleY();

        // 3. Apply rotation
        if (transform.getRotationDegrees() != 0) {
            double angleRadians = Math.toRadians(transform.getRotationDegrees());
            double cos = Math.cos(angleRadians);
            double sin = Math.sin(angleRadians);

            double rotatedX = tx * cos - ty * sin;
            double rotatedY = tx * sin + ty * cos;

            tx = rotatedX;
            ty = rotatedY;
        }

        // 4. Translate back from origin
        tx += pivotX;
        ty += pivotY;

        // 5. Apply translation offset
        tx += transform.getTranslateX();
        ty += transform.getTranslateY();

        // Round to nearest pixel (nearest-neighbor interpolation)
        return new Point((int) Math.round(tx), (int) Math.round(ty));
    }

    /**
     * Creates a transformed selection region.
     * Calculates actual bounding box of transformed pixels.
     *
     * @param originalSelection The original selection
     * @param transform The transformation state
     * @return Transformed selection region (rectangular bounding box)
     */
    public SelectionRegion transformSelection(SelectionRegion originalSelection, TransformState transform) {
        if (originalSelection == null) {
            return null;
        }

        // Extract pixels and transform them to get actual bounding box
        Rectangle originalBounds = originalSelection.getBounds();

        // Get pivot point from transform (convert from absolute to relative coordinates)
        Point absolutePivot = transform.getPivot();
        int pivotX = absolutePivot.x - originalBounds.x;
        int pivotY = absolutePivot.y - originalBounds.y;

        // Find min/max coordinates of transformed bounds
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        // Transform the four extent corners to find bounding box
        // Use width and height (not width-1, height-1) to get the actual extent of the region
        Point[] corners = new Point[] {
            new Point(0, 0),  // Top-left extent
            new Point(originalBounds.width, 0),  // Top-right extent
            new Point(0, originalBounds.height),  // Bottom-left extent
            new Point(originalBounds.width, originalBounds.height)  // Bottom-right extent
        };

        for (Point corner : corners) {
            Point transformed = transformPoint(corner.x, corner.y, pivotX, pivotY, transform);

            // Convert to absolute canvas coordinates
            int absoluteX = originalBounds.x + transformed.x;
            int absoluteY = originalBounds.y + transformed.y;

            minX = Math.min(minX, absoluteX);
            minY = Math.min(minY, absoluteY);
            maxX = Math.max(maxX, absoluteX);
            maxY = Math.max(maxY, absoluteY);
        }

        // Create rectangular selection from bounding box
        // Width and height are the difference between max and min (extent size)
        Rectangle transformedBounds = new Rectangle(minX, minY, maxX - minX, maxY - minY);
        return new com.openmason.ui.components.textureCreator.selection.RectangularSelection(transformedBounds);
    }

    /**
     * Calculates the bounding box of transformed pixels.
     * Useful for determining canvas bounds after transformation.
     *
     * @param transformedPixels Map of absolute canvas coordinates to colors
     * @return Bounding rectangle, or null if no pixels
     */
    public Rectangle calculateTransformedBounds(Map<Point, Integer> transformedPixels) {
        if (transformedPixels.isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Point point : transformedPixels.keySet()) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
}
