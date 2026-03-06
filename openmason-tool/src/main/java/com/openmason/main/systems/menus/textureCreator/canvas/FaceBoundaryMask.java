package com.openmason.main.systems.menus.textureCreator.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines the paintable area on the canvas based on a face's geometric shape.
 *
 * <p>Projects a face polygon (arbitrary convex or concave) into canvas pixel
 * coordinates and rasterizes it into a boolean mask. Pixels outside the mask
 * are rejected by {@link PixelCanvas#setPixel}, preventing painting outside
 * the face's actual boundaries within its UV region.
 *
 * <p>The mask is generated via scanline rasterization and supports triangles,
 * quads, pentagons, hexagons — any polygon the knife tool can produce.
 *
 * @see PixelCanvas
 * @see FaceBoundaryRenderer
 */
public class FaceBoundaryMask {

    private static final Logger logger = LoggerFactory.getLogger(FaceBoundaryMask.class);

    private final int width;
    private final int height;
    private final boolean[] mask;
    private final float[] polygonXCoords;
    private final float[] polygonYCoords;
    private final int vertexCount;

    /**
     * Create a face boundary mask from polygon vertices in canvas pixel coordinates.
     *
     * @param width          canvas width in pixels
     * @param height         canvas height in pixels
     * @param polygonXCoords X coordinates of polygon vertices in canvas space
     * @param polygonYCoords Y coordinates of polygon vertices in canvas space
     */
    public FaceBoundaryMask(int width, int height, float[] polygonXCoords, float[] polygonYCoords) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Mask dimensions must be positive: " + width + "x" + height);
        }
        if (polygonXCoords.length != polygonYCoords.length || polygonXCoords.length < 3) {
            throw new IllegalArgumentException(
                "Polygon must have at least 3 vertices with matching X/Y arrays, got: " + polygonXCoords.length);
        }

        this.width = width;
        this.height = height;
        this.polygonXCoords = polygonXCoords.clone();
        this.polygonYCoords = polygonYCoords.clone();
        this.vertexCount = polygonXCoords.length;
        this.mask = new boolean[width * height];

        rasterize();
        logger.debug("Face boundary mask created: {}x{}, {} vertices, {} pixels inside",
            width, height, vertexCount, countInsidePixels());
    }

    /**
     * Create a mask from a UV region and 3D face vertices projected to 2D.
     *
     * <p>Maps 2D polygon coordinates (already projected from 3D) into canvas
     * pixel space using the UV region's bounding rectangle.
     *
     * @param canvasWidth    full canvas width in pixels
     * @param canvasHeight   full canvas height in pixels
     * @param uvU0           UV region left (0.0–1.0)
     * @param uvV0           UV region top (0.0–1.0)
     * @param uvU1           UV region right (0.0–1.0)
     * @param uvV1           UV region bottom (0.0–1.0)
     * @param localXCoords   polygon X coordinates in local 2D face space (0.0–1.0 normalized)
     * @param localYCoords   polygon Y coordinates in local 2D face space (0.0–1.0 normalized)
     * @return the rasterized boundary mask
     */
    public static FaceBoundaryMask fromUVRegion(int canvasWidth, int canvasHeight,
                                                 float uvU0, float uvV0,
                                                 float uvU1, float uvV1,
                                                 float[] localXCoords, float[] localYCoords) {
        float regionPixelX = uvU0 * canvasWidth;
        float regionPixelY = uvV0 * canvasHeight;
        float regionPixelW = (uvU1 - uvU0) * canvasWidth;
        float regionPixelH = (uvV1 - uvV0) * canvasHeight;

        float[] canvasXCoords = new float[localXCoords.length];
        float[] canvasYCoords = new float[localYCoords.length];

        for (int i = 0; i < localXCoords.length; i++) {
            canvasXCoords[i] = regionPixelX + localXCoords[i] * regionPixelW;
            canvasYCoords[i] = regionPixelY + localYCoords[i] * regionPixelH;
        }

        return new FaceBoundaryMask(canvasWidth, canvasHeight, canvasXCoords, canvasYCoords);
    }

    /**
     * Check if a pixel coordinate is inside the face boundary.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return true if the pixel is inside the mask
     */
    public boolean contains(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }
        return mask[y * width + x];
    }

    /**
     * Get mask width.
     * @return width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get mask height.
     * @return height in pixels
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get the polygon X coordinates (canvas space).
     * @return copy of X coordinate array
     */
    public float[] getPolygonXCoords() {
        return polygonXCoords.clone();
    }

    /**
     * Get the polygon Y coordinates (canvas space).
     * @return copy of Y coordinate array
     */
    public float[] getPolygonYCoords() {
        return polygonYCoords.clone();
    }

    /**
     * Get number of polygon vertices.
     * @return vertex count
     */
    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Rasterize the polygon into the boolean mask using scanline fill.
     *
     * <p>Uses the ray-casting (even-odd) rule: for each pixel center, cast a
     * horizontal ray and count edge crossings. An odd count means the point
     * is inside the polygon. This correctly handles concave polygons.
     */
    private void rasterize() {
        // Determine the scanline range from polygon bounds
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;

        for (int i = 0; i < vertexCount; i++) {
            minY = Math.min(minY, polygonYCoords[i]);
            maxY = Math.max(maxY, polygonYCoords[i]);
            minX = Math.min(minX, polygonXCoords[i]);
            maxX = Math.max(maxX, polygonXCoords[i]);
        }

        // Clamp to canvas bounds
        int startY = Math.max(0, (int) Math.floor(minY));
        int endY = Math.min(height - 1, (int) Math.ceil(maxY));
        int startX = Math.max(0, (int) Math.floor(minX));
        int endX = Math.min(width - 1, (int) Math.ceil(maxX));

        // For each pixel in the bounding box, test if its center is inside the polygon
        for (int y = startY; y <= endY; y++) {
            float testY = y + 0.5f; // Pixel center
            for (int x = startX; x <= endX; x++) {
                float testX = x + 0.5f; // Pixel center
                if (isPointInPolygon(testX, testY)) {
                    mask[y * width + x] = true;
                }
            }
        }
    }

    /**
     * Test if a point is inside the polygon using the ray-casting (even-odd) algorithm.
     *
     * @param testX point X coordinate
     * @param testY point Y coordinate
     * @return true if the point is inside the polygon
     */
    private boolean isPointInPolygon(float testX, float testY) {
        boolean inside = false;

        for (int i = 0, j = vertexCount - 1; i < vertexCount; j = i++) {
            float xi = polygonXCoords[i];
            float yi = polygonYCoords[i];
            float xj = polygonXCoords[j];
            float yj = polygonYCoords[j];

            // Check if the ray from (testX, testY) going right crosses this edge
            if ((yi > testY) != (yj > testY)) {
                float intersectX = xi + (testY - yi) / (yj - yi) * (xj - xi);
                if (testX < intersectX) {
                    inside = !inside;
                }
            }
        }

        return inside;
    }

    private int countInsidePixels() {
        int count = 0;
        for (boolean b : mask) {
            if (b) count++;
        }
        return count;
    }
}
