package com.openmason.main.systems.menus.textureCreator.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polygon-based shape mask that rasterizes an arbitrary polygon into an
 * editable pixel region.
 *
 * <p>Projects a face polygon (arbitrary convex or concave) into canvas pixel
 * coordinates and rasterizes it into a boolean mask. Pixels outside the polygon
 * are rejected by {@link PixelCanvas#setPixel}, preventing painting outside
 * the face's actual boundaries.
 *
 * <p>The mask is generated via ray-casting (even-odd rule) and supports
 * triangles, quads, pentagons, hexagons — any polygon shape.
 *
 * @see CanvasShapeMask
 * @see PixelCanvas
 */
public class PolygonShapeMask implements CanvasShapeMask {

    private static final Logger logger = LoggerFactory.getLogger(PolygonShapeMask.class);

    private final int width;
    private final int height;
    private final boolean[] mask;
    private final float[] coverageMap;
    private final float[] polygonXCoords;
    private final float[] polygonYCoords;
    private final int vertexCount;

    /**
     * Create a polygon shape mask from vertices in canvas pixel coordinates.
     *
     * @param width          canvas width in pixels
     * @param height         canvas height in pixels
     * @param polygonXCoords X coordinates of polygon vertices in canvas space
     * @param polygonYCoords Y coordinates of polygon vertices in canvas space
     */
    public PolygonShapeMask(int width, int height, float[] polygonXCoords, float[] polygonYCoords) {
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
        this.coverageMap = new float[width * height];

        rasterize();
        logger.debug("Polygon shape mask created: {}x{}, {} vertices, {} pixels inside",
            width, height, vertexCount, countInsidePixels());
    }

    /**
     * Create a mask from a UV region and 2D polygon coordinates.
     *
     * <p>Maps normalized polygon coordinates (0.0–1.0 local face space) into
     * canvas pixel space using the UV region's bounding rectangle.
     *
     * @param canvasWidth    full canvas width in pixels
     * @param canvasHeight   full canvas height in pixels
     * @param uvU0           UV region left (0.0–1.0)
     * @param uvV0           UV region top (0.0–1.0)
     * @param uvU1           UV region right (0.0–1.0)
     * @param uvV1           UV region bottom (0.0–1.0)
     * @param localXCoords   polygon X coordinates in local 2D face space (0.0–1.0)
     * @param localYCoords   polygon Y coordinates in local 2D face space (0.0–1.0)
     * @return the rasterized polygon shape mask
     */
    public static PolygonShapeMask fromUVRegion(int canvasWidth, int canvasHeight,
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

        return new PolygonShapeMask(canvasWidth, canvasHeight, canvasXCoords, canvasYCoords);
    }

    @Override
    public boolean isEditable(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }
        return mask[y * width + x];
    }

    @Override
    public float getCoverage(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return 0.0f;
        }
        return coverageMap[y * width + x];
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    /**
     * Get the polygon X coordinates (canvas space).
     *
     * @return copy of X coordinate array
     */
    public float[] getPolygonXCoords() {
        return polygonXCoords.clone();
    }

    /**
     * Get the polygon Y coordinates (canvas space).
     *
     * @return copy of Y coordinate array
     */
    public float[] getPolygonYCoords() {
        return polygonYCoords.clone();
    }

    /**
     * Get number of polygon vertices.
     *
     * @return vertex count
     */
    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Rasterize the polygon into the boolean mask and coverage map.
     *
     * <p>Uses ray-casting (even-odd rule) for interior detection, then
     * computes signed distance to polygon edges for boundary pixels.
     * Pixels fully inside get coverage 1.0, pixels near edges get
     * fractional coverage via smoothstep, and pixels outside get 0.0.
     * The boolean mask is true for any pixel with coverage &gt; 0.
     */
    private void rasterize() {
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

        // Expand bounds by 1 pixel to catch partial-coverage edge pixels
        int startY = Math.max(0, (int) Math.floor(minY) - 1);
        int endY = Math.min(height - 1, (int) Math.ceil(maxY) + 1);
        int startX = Math.max(0, (int) Math.floor(minX) - 1);
        int endX = Math.min(width - 1, (int) Math.ceil(maxX) + 1);

        for (int y = startY; y <= endY; y++) {
            float testY = y + 0.5f;
            for (int x = startX; x <= endX; x++) {
                float testX = x + 0.5f;

                boolean inside = isPointInPolygon(testX, testY);

                // Compute minimum distance to any polygon edge
                float minDist = computeMinEdgeDistance(testX, testY);

                float coverage;
                if (inside) {
                    // Inside: full coverage unless near an edge
                    coverage = CoverageBlender.smoothstep(0.0f, 1.0f, minDist + 0.5f);
                } else {
                    // Outside: partial coverage only if very close to edge
                    coverage = CoverageBlender.smoothstep(1.0f, 0.0f, minDist + 0.5f);
                }

                int index = y * width + x;
                coverageMap[index] = coverage;
                mask[index] = coverage > 0.0f;
            }
        }
    }

    /**
     * Compute the minimum distance from a point to any polygon edge.
     */
    private float computeMinEdgeDistance(float px, float py) {
        float minDist = Float.MAX_VALUE;
        for (int i = 0, j = vertexCount - 1; i < vertexCount; j = i++) {
            float dist = CoverageBlender.pointToSegmentDistance(
                px, py,
                polygonXCoords[j], polygonYCoords[j],
                polygonXCoords[i], polygonYCoords[i]
            );
            if (dist < minDist) {
                minDist = dist;
            }
        }
        return minDist;
    }

    private boolean isPointInPolygon(float testX, float testY) {
        boolean inside = false;

        for (int i = 0, j = vertexCount - 1; i < vertexCount; j = i++) {
            float xi = polygonXCoords[i];
            float yi = polygonYCoords[i];
            float xj = polygonXCoords[j];
            float yj = polygonYCoords[j];

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
