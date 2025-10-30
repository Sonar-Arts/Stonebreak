package com.openmason.ui.components.textureCreator.tools.move;

import java.awt.Rectangle;

/**
 * Geometry helper utilities used by the move tool. Centralising all maths in a
 * single class keeps transformation logic easy to audit and extend.
 */
final class TransformMath {

    private TransformMath() {
    }

    static double[] mapLocalToCanvas(double localX,
                                     double localY,
                                     SelectionSnapshot snapshot,
                                     TransformationState transform) {
        return mapLocalToCanvas(localX, localY, snapshot.bounds(), transform);
    }

    static double[] mapLocalToCanvas(double localX,
                                     double localY,
                                     Rectangle bounds,
                                     TransformationState transform) {

        double centreX = bounds.width / 2.0;
        double centreY = bounds.height / 2.0;

        double nx = localX - centreX;
        double ny = localY - centreY;

        double scaledX = nx * transform.scaleX();
        double scaledY = ny * transform.scaleY();

        double radians = Math.toRadians(transform.rotationDegrees());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        double rotatedX = scaledX * cos - scaledY * sin;
        double rotatedY = scaledX * sin + scaledY * cos;

        double canvasX = bounds.x + centreX + rotatedX + transform.translateX();
        double canvasY = bounds.y + centreY + rotatedY + transform.translateY();

        return new double[]{canvasX, canvasY};
    }

    static double[] mapCanvasToLocal(double canvasX,
                                     double canvasY,
                                     SelectionSnapshot snapshot,
                                     TransformationState transform) {

        Rectangle bounds = snapshot.bounds();
        double centreX = bounds.width / 2.0;
        double centreY = bounds.height / 2.0;

        double dx = canvasX - (bounds.x + centreX) - transform.translateX();
        double dy = canvasY - (bounds.y + centreY) - transform.translateY();

        double radians = Math.toRadians(transform.rotationDegrees());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        double unrotX = dx * cos + dy * sin;
        double unrotY = -dx * sin + dy * cos;

        double localX = unrotX / transform.scaleX() + centreX;
        double localY = unrotY / transform.scaleY() + centreY;

        return new double[]{localX, localY};
    }

    static TransformedImage computeTransformedImage(SelectionSnapshot snapshot,
                                                    TransformationState transform) {
        if (transform.isIdentity()) {
            Rectangle bounds = snapshot.bounds();
            int[] colours = snapshot.pixels().clone();
            boolean[] mask = snapshot.mask().clone();
            int count = 0;
            for (boolean inside : mask) {
                if (inside) {
                    count++;
                }
            }
            return new TransformedImage(bounds, colours, mask, count);
        }

        Rectangle sourceBounds = snapshot.bounds();
        // Transform corner edges directly (no expansion needed)
        double[][] corners = new double[][]{
                mapLocalToCanvas(0.0, 0.0, snapshot, transform),
                mapLocalToCanvas(sourceBounds.width, 0.0, snapshot, transform),
                mapLocalToCanvas(sourceBounds.width, sourceBounds.height, snapshot, transform),
                mapLocalToCanvas(0.0, sourceBounds.height, snapshot, transform)
        };

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (double[] corner : corners) {
            minX = Math.min(minX, corner[0]);
            maxX = Math.max(maxX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxY = Math.max(maxY, corner[1]);
        }

        int canvasWidth = snapshot.canvasWidth();
        int canvasHeight = snapshot.canvasHeight();

        int destMinX = clamp((int) Math.floor(minX), 0, canvasWidth - 1);
        int destMaxX = clamp((int) Math.ceil(maxX) - 1, 0, canvasWidth - 1);
        int destMinY = clamp((int) Math.floor(minY), 0, canvasHeight - 1);
        int destMaxY = clamp((int) Math.ceil(maxY) - 1, 0, canvasHeight - 1);

        if (destMaxX < destMinX || destMaxY < destMinY) {
            Rectangle empty = new Rectangle(destMinX, destMinY, 0, 0);
            return new TransformedImage(empty, new int[0], new boolean[0], 0);
        }

        int destWidth = destMaxX - destMinX + 1;
        int destHeight = destMaxY - destMinY + 1;
        int[] destPixels = new int[destWidth * destHeight];
        boolean[] destMask = new boolean[destWidth * destHeight];

        int pixelCount = 0;

        int minDx = destWidth;
        int minDy = destHeight;
        int maxDx = -1;
        int maxDy = -1;

        for (int dy = 0; dy < destHeight; dy++) {
            int canvasY = destMinY + dy;
            double sampleY = canvasY + 0.5;

            for (int dx = 0; dx < destWidth; dx++) {
                int canvasX = destMinX + dx;
                double sampleX = canvasX + 0.5;

                double[] local = mapCanvasToLocal(sampleX, sampleY, snapshot, transform);

                // Check bounds BEFORE rounding to avoid replicating edge pixels
                // Allow small tolerance for floating-point precision (0.5 is the rounding threshold)
                if (local[0] < -0.5 || local[0] >= snapshot.width() - 0.5 ||
                    local[1] < -0.5 || local[1] >= snapshot.height() - 0.5) {
                    continue;
                }

                int localX = (int) Math.round(local[0]);
                int localY = (int) Math.round(local[1]);

                int sourceIndex = snapshot.indexFor(localX, localY);
                if (!snapshot.mask()[sourceIndex]) {
                    continue;
                }

                int destIndex = dy * destWidth + dx;

                destMask[destIndex] = true;
                destPixels[destIndex] = snapshot.pixels()[sourceIndex];
                pixelCount++;

                 if (dx < minDx) minDx = dx;
                 if (dx > maxDx) maxDx = dx;
                 if (dy < minDy) minDy = dy;
                 if (dy > maxDy) maxDy = dy;
            }
        }

        if (pixelCount == 0) {
            Rectangle empty = new Rectangle(destMinX, destMinY, 0, 0);
            return new TransformedImage(empty, new int[0], new boolean[0], 0);
        }

        if (minDx == 0 && minDy == 0 && maxDx == destWidth - 1 && maxDy == destHeight - 1) {
            Rectangle destBounds = new Rectangle(destMinX, destMinY, destWidth, destHeight);
            return new TransformedImage(destBounds, destPixels, destMask, pixelCount);
        }

        int croppedWidth = maxDx - minDx + 1;
        int croppedHeight = maxDy - minDy + 1;
        int[] croppedPixels = new int[croppedWidth * croppedHeight];
        boolean[] croppedMask = new boolean[croppedWidth * croppedHeight];

        for (int y = 0; y < croppedHeight; y++) {
            int srcY = minDy + y;
            for (int x = 0; x < croppedWidth; x++) {
                int srcX = minDx + x;
                int srcIndex = srcY * destWidth + srcX;
                int dstIndex = y * croppedWidth + x;
                croppedPixels[dstIndex] = destPixels[srcIndex];
                croppedMask[dstIndex] = destMask[srcIndex];
            }
        }

        Rectangle destBounds = new Rectangle(destMinX + minDx, destMinY + minDy, croppedWidth, croppedHeight);
        return new TransformedImage(destBounds, croppedPixels, croppedMask, pixelCount);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
