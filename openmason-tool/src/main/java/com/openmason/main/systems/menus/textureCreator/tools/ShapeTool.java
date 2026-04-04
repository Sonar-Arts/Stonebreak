package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorPreferences;
import com.openmason.main.systems.menus.textureCreator.canvas.CoverageBlender;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;

import java.util.HashMap;
import java.util.Map;

/**
 * Shape tool - draws rectangles, ellipses, and triangles with real-time preview.
 * Supports both filled and outline modes via preferences.
 *
 * @author Open Mason Team
 */
public class ShapeTool implements DrawingTool {

    public enum ShapeType {
        RECTANGLE("Rectangle"),
        ELLIPSE("Ellipse"),
        TRIANGLE("Triangle");

        private final String displayName;

        ShapeType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private ShapeType currentShape = ShapeType.RECTANGLE;

    private int startX = -1;
    private int startY = -1;
    private int endX = -1;
    private int endY = -1;
    private int lastPreviewEndX = -1;
    private int lastPreviewEndY = -1;

    private final Map<Integer, Integer> originalPixels = new HashMap<>();
    private PixelCanvas previewCanvas = null;

    private TextureCreatorPreferences preferences;

    public void setPreferences(TextureCreatorPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        startX = x;
        startY = y;
        endX = x;
        endY = y;
        lastPreviewEndX = x;
        lastPreviewEndY = y;
        previewCanvas = canvas;
        originalPixels.clear();

        saveOriginalPixel(x, y, canvas);
        canvas.setPixel(x, y, color);
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        endX = x;
        endY = y;

        if (endX != lastPreviewEndX || endY != lastPreviewEndY) {
            restoreOriginalPixels(canvas);
            drawShapePreview(startX, startY, endX, endY, color, canvas);
            lastPreviewEndX = endX;
            lastPreviewEndY = endY;
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        restoreOriginalPixels(canvas);

        if (startX != -1 && startY != -1 && endX != -1 && endY != -1) {
            drawShapeFinal(startX, startY, endX, endY, color, canvas, command);
        }
        reset();
    }

    private void drawShapePreview(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas) {
        boolean filled = preferences != null && preferences.isShapeToolFillMode();

        switch (currentShape) {
            case RECTANGLE -> drawRectangle(x0, y0, x1, y1, color, canvas, null, filled, true);
            case ELLIPSE -> drawEllipse(x0, y0, x1, y1, color, canvas, null, filled, true);
            case TRIANGLE -> drawTriangle(x0, y0, x1, y1, color, canvas, null, filled, true);
        }
    }

    private void drawShapeFinal(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas, DrawCommand command) {
        boolean filled = preferences != null && preferences.isShapeToolFillMode();

        switch (currentShape) {
            case RECTANGLE -> drawRectangle(x0, y0, x1, y1, color, canvas, command, filled, false);
            case ELLIPSE -> drawEllipse(x0, y0, x1, y1, color, canvas, command, filled, false);
            case TRIANGLE -> drawTriangle(x0, y0, x1, y1, color, canvas, command, filled, false);
        }
    }

    private void drawRectangle(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas,
                               DrawCommand command, boolean filled, boolean isPreview) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1);
        int maxY = Math.max(y0, y1);

        // Rectangles have axis-aligned edges, so full coverage for all pixels
        // (no sub-pixel benefit for straight horizontal/vertical edges).
        // Mask coverage is still applied for smooth polygon boundaries.
        if (filled) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (isPreview) {
                        setPixelPreview(x, y, color, 1.0f, canvas);
                    } else {
                        setPixelWithCoverage(x, y, color, 1.0f, canvas, command);
                    }
                }
            }
        } else {
            for (int x = minX; x <= maxX; x++) {
                if (isPreview) {
                    setPixelPreview(x, minY, color, 1.0f, canvas);
                    setPixelPreview(x, maxY, color, 1.0f, canvas);
                } else {
                    setPixelWithCoverage(x, minY, color, 1.0f, canvas, command);
                    setPixelWithCoverage(x, maxY, color, 1.0f, canvas, command);
                }
            }
            for (int y = minY + 1; y < maxY; y++) {
                if (isPreview) {
                    setPixelPreview(minX, y, color, 1.0f, canvas);
                    setPixelPreview(maxX, y, color, 1.0f, canvas);
                } else {
                    setPixelWithCoverage(minX, y, color, 1.0f, canvas, command);
                    setPixelWithCoverage(maxX, y, color, 1.0f, canvas, command);
                }
            }
        }
    }

    private void drawEllipse(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas,
                            DrawCommand command, boolean filled, boolean isPreview) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1);
        int maxY = Math.max(y0, y1);

        float centerX = (minX + maxX) / 2.0f;
        float centerY = (minY + maxY) / 2.0f;
        float radiusX = (maxX - minX) / 2.0f;
        float radiusY = (maxY - minY) / 2.0f;

        if (radiusX < 0.5f && radiusY < 0.5f) {
            if (isPreview) {
                setPixelPreview((int) centerX, (int) centerY, color, 1.0f, canvas);
            } else {
                setPixelWithCoverage((int) centerX, (int) centerY, color, 1.0f, canvas, command);
            }
            return;
        }

        // Distance-based ellipse with smooth edges
        // Scan bounding box + 1 pixel margin for partial coverage
        int scanMinX = Math.max(0, minX - 1);
        int scanMaxX = Math.min(canvas.getWidth() - 1, maxX + 1);
        int scanMinY = Math.max(0, minY - 1);
        int scanMaxY = Math.min(canvas.getHeight() - 1, maxY + 1);

        // Avoid division by zero for degenerate ellipses
        float rx = Math.max(radiusX, 0.5f);
        float ry = Math.max(radiusY, 0.5f);

        for (int y = scanMinY; y <= scanMaxY; y++) {
            for (int x = scanMinX; x <= scanMaxX; x++) {
                float dx = x - centerX;
                float dy = y - centerY;
                // Normalized distance from center (1.0 = on the ellipse boundary)
                float dist = (float) Math.sqrt((dx * dx) / (rx * rx) + (dy * dy) / (ry * ry));

                float coverage;
                if (filled) {
                    // Filled: full inside, smooth falloff at boundary
                    coverage = CoverageBlender.smoothstep(1.0f + 0.7f / Math.max(rx, ry),
                                                          1.0f - 0.7f / Math.max(rx, ry), dist);
                } else {
                    // Outline: ring of ~1px width at the boundary
                    float halfWidth = 0.7f / Math.max(rx, ry);
                    coverage = 1.0f - Math.abs(dist - 1.0f) / halfWidth;
                    coverage = Math.clamp(coverage, 0.0f, 1.0f);
                }

                if (coverage > 0.01f) {
                    if (isPreview) {
                        setPixelPreview(x, y, color, coverage, canvas);
                    } else {
                        setPixelWithCoverage(x, y, color, coverage, canvas, command);
                    }
                }
            }
        }
    }

    private void drawTriangle(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas,
                             DrawCommand command, boolean filled, boolean isPreview) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1);
        int maxY = Math.max(y0, y1);

        float apexX = (minX + maxX) / 2.0f;

        // Triangle vertices
        float[] triX = {apexX, (float) minX, (float) maxX};
        float[] triY = {(float) minY, (float) maxY, (float) maxY};

        // Scan bounding box + 1 pixel margin
        int scanMinX = Math.max(0, minX - 1);
        int scanMaxX = Math.min(canvas.getWidth() - 1, maxX + 1);
        int scanMinY = Math.max(0, minY - 1);
        int scanMaxY = Math.min(canvas.getHeight() - 1, maxY + 1);

        for (int y = scanMinY; y <= scanMaxY; y++) {
            for (int x = scanMinX; x <= scanMaxX; x++) {
                float px = x + 0.0f;
                float py = y + 0.0f;

                // Compute minimum distance to any triangle edge
                float minDist = Float.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    int j = (i + 1) % 3;
                    float d = CoverageBlender.pointToSegmentDistance(px, py, triX[i], triY[i], triX[j], triY[j]);
                    if (d < minDist) {
                        minDist = d;
                    }
                }

                // Check if point is inside the triangle using cross-product sign test
                boolean inside = isPointInTriangle(px, py, triX, triY);

                float coverage;
                if (filled) {
                    if (inside) {
                        coverage = CoverageBlender.smoothstep(0.0f, 1.0f, minDist + 0.5f);
                    } else {
                        coverage = CoverageBlender.smoothstep(1.0f, 0.0f, minDist + 0.5f);
                    }
                } else {
                    // Outline: ~1px wide line at edges
                    coverage = CoverageBlender.smoothstep(1.0f, 0.0f, minDist);
                }

                if (coverage > 0.01f) {
                    if (isPreview) {
                        setPixelPreview(x, y, color, coverage, canvas);
                    } else {
                        setPixelWithCoverage(x, y, color, coverage, canvas, command);
                    }
                }
            }
        }
    }

    /**
     * Check if a point is inside a triangle using barycentric coordinates.
     */
    private boolean isPointInTriangle(float px, float py, float[] triX, float[] triY) {
        float d1 = sign(px, py, triX[0], triY[0], triX[1], triY[1]);
        float d2 = sign(px, py, triX[1], triY[1], triX[2], triY[2]);
        float d3 = sign(px, py, triX[2], triY[2], triX[0], triY[0]);

        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);

        return !(hasNeg && hasPos);
    }

    private float sign(float px, float py, float x1, float y1, float x2, float y2) {
        return (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2);
    }

    private void setPixelPreview(int x, int y, int color, float coverage, PixelCanvas canvas) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        float maskCoverage = canvas.getMaskCoverage(x, y);
        float finalCoverage = coverage * maskCoverage;
        if (finalCoverage <= 0.0f) {
            return;
        }

        saveOriginalPixel(x, y, canvas);

        int existingColor = canvas.getPixel(x, y);
        int blended;
        if (finalCoverage >= 1.0f) {
            blended = color;
        } else {
            blended = CoverageBlender.blendWithCoverage(color, existingColor, finalCoverage);
        }
        canvas.setPixel(x, y, blended);
    }

    private void setPixelWithCoverage(int x, int y, int color, float coverage,
                                       PixelCanvas canvas, DrawCommand command) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        float maskCoverage = canvas.getMaskCoverage(x, y);
        float finalCoverage = coverage * maskCoverage;
        if (finalCoverage <= 0.0f) {
            return;
        }

        int oldColor = canvas.getPixel(x, y);
        int blended;
        if (finalCoverage >= 1.0f) {
            blended = color;
        } else {
            blended = CoverageBlender.blendWithCoverage(color, oldColor, finalCoverage);
        }

        if (blended == oldColor) {
            return;
        }

        if (command != null) {
            command.recordPixelChange(x, y, oldColor, blended);
        }
        canvas.setPixel(x, y, blended);
    }

    private void saveOriginalPixel(int x, int y, PixelCanvas canvas) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }
        int key = y * canvas.getWidth() + x;
        if (!originalPixels.containsKey(key)) {
            originalPixels.put(key, canvas.getPixel(x, y));
        }
    }

    private void restoreOriginalPixels(PixelCanvas canvas) {
        for (Map.Entry<Integer, Integer> entry : originalPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            canvas.setPixel(x, y, entry.getValue());
        }
        originalPixels.clear();
    }

    @Override
    public void reset() {
        startX = -1;
        startY = -1;
        endX = -1;
        endY = -1;
        lastPreviewEndX = -1;
        lastPreviewEndY = -1;
        originalPixels.clear();
        previewCanvas = null;
    }

    @Override
    public String getName() {
        return "Shapes";
    }

    @Override
    public String getDescription() {
        return "Draw shapes (right-click icon for variants)";
    }

    public ShapeType getCurrentShape() {
        return currentShape;
    }

    public void setCurrentShape(ShapeType shape) {
        this.currentShape = shape;
    }
}
