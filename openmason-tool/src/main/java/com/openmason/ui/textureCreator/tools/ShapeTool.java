package com.openmason.ui.textureCreator.tools;

import com.openmason.ui.textureCreator.TextureCreatorPreferences;
import com.openmason.ui.textureCreator.canvas.CubeNetValidator;
import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.commands.DrawCommand;

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

        if (filled) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (isPreview) {
                        setPixelPreview(x, y, color, canvas);
                    } else {
                        setPixelWithUndo(x, y, color, canvas, command);
                    }
                }
            }
        } else {
            for (int x = minX; x <= maxX; x++) {
                if (isPreview) {
                    setPixelPreview(x, minY, color, canvas);
                    setPixelPreview(x, maxY, color, canvas);
                } else {
                    setPixelWithUndo(x, minY, color, canvas, command);
                    setPixelWithUndo(x, maxY, color, canvas, command);
                }
            }
            for (int y = minY + 1; y < maxY; y++) {
                if (isPreview) {
                    setPixelPreview(minX, y, color, canvas);
                    setPixelPreview(maxX, y, color, canvas);
                } else {
                    setPixelWithUndo(minX, y, color, canvas, command);
                    setPixelWithUndo(maxX, y, color, canvas, command);
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

        int centerX = (minX + maxX) / 2;
        int centerY = (minY + maxY) / 2;
        int radiusX = (maxX - minX) / 2;
        int radiusY = (maxY - minY) / 2;

        if (radiusX == 0 && radiusY == 0) {
            if (isPreview) {
                setPixelPreview(centerX, centerY, color, canvas);
            } else {
                setPixelWithUndo(centerX, centerY, color, canvas, command);
            }
            return;
        }

        if (radiusX == 0 || radiusY == 0) {
            if (radiusX == 0 && radiusY > 0) {
                for (int dy = -radiusY; dy <= radiusY; dy++) {
                    if (filled) {
                        if (isPreview) {
                            setPixelPreview(centerX, centerY + dy, color, canvas);
                        } else {
                            setPixelWithUndo(centerX, centerY + dy, color, canvas, command);
                        }
                    } else {
                        if (dy == -radiusY || dy == radiusY) {
                            if (isPreview) {
                                setPixelPreview(centerX, centerY + dy, color, canvas);
                            } else {
                                setPixelWithUndo(centerX, centerY + dy, color, canvas, command);
                            }
                        }
                    }
                }
            } else if (radiusY == 0 && radiusX > 0) {
                for (int dx = -radiusX; dx <= radiusX; dx++) {
                    if (filled || dx == -radiusX || dx == radiusX) {
                        if (isPreview) {
                            setPixelPreview(centerX + dx, centerY, color, canvas);
                        } else {
                            setPixelWithUndo(centerX + dx, centerY, color, canvas, command);
                        }
                    }
                }
            }
            return;
        }

        if (filled) {
            drawFilledEllipse(centerX, centerY, radiusX, radiusY, color, canvas, command, isPreview);
        } else {
            drawEllipseOutline(centerX, centerY, radiusX, radiusY, color, canvas, command, isPreview);
        }
    }

    private void drawEllipseOutline(int centerX, int centerY, int radiusX, int radiusY,
                                   int color, PixelCanvas canvas, DrawCommand command, boolean isPreview) {
        int x = 0;
        int y = radiusY;
        long rx2 = (long)radiusX * radiusX;
        long ry2 = (long)radiusY * radiusY;
        long twoRx2 = 2 * rx2;
        long twoRy2 = 2 * ry2;
        long p;
        long px = 0;
        long py = twoRx2 * y;

        plotEllipsePoints(centerX, centerY, x, y, color, canvas, command, isPreview);

        p = Math.round(ry2 - (rx2 * radiusY) + (0.25 * rx2));
        while (px < py) {
            x++;
            px += twoRy2;
            if (p < 0) {
                p += ry2 + px;
            } else {
                y--;
                py -= twoRx2;
                p += ry2 + px - py;
            }
            plotEllipsePoints(centerX, centerY, x, y, color, canvas, command, isPreview);
        }

        p = Math.round(ry2 * (x + 0.5) * (x + 0.5) + rx2 * (y - 1) * (y - 1) - rx2 * ry2);
        while (y > 0) {
            y--;
            py -= twoRx2;
            if (p > 0) {
                p += rx2 - py;
            } else {
                x++;
                px += twoRy2;
                p += rx2 - py + px;
            }
            plotEllipsePoints(centerX, centerY, x, y, color, canvas, command, isPreview);
        }
    }

    private void drawFilledEllipse(int centerX, int centerY, int radiusX, int radiusY,
                                  int color, PixelCanvas canvas, DrawCommand command, boolean isPreview) {
        int x = 0;
        int y = radiusY;
        long rx2 = (long)radiusX * radiusX;
        long ry2 = (long)radiusY * radiusY;
        long twoRx2 = 2 * rx2;
        long twoRy2 = 2 * ry2;
        long p;
        long px = 0;
        long py = twoRx2 * y;

        fillEllipseSpans(centerX, centerY, x, y, color, canvas, command, isPreview);

        p = Math.round(ry2 - (rx2 * radiusY) + (0.25 * rx2));
        while (px < py) {
            x++;
            px += twoRy2;
            if (p < 0) {
                p += ry2 + px;
            } else {
                y--;
                py -= twoRx2;
                p += ry2 + px - py;
            }
            fillEllipseSpans(centerX, centerY, x, y, color, canvas, command, isPreview);
        }

        p = Math.round(ry2 * (x + 0.5) * (x + 0.5) + rx2 * (y - 1) * (y - 1) - rx2 * ry2);
        while (y > 0) {
            y--;
            py -= twoRx2;
            if (p > 0) {
                p += rx2 - py;
            } else {
                x++;
                px += twoRy2;
                p += rx2 - py + px;
            }
            fillEllipseSpans(centerX, centerY, x, y, color, canvas, command, isPreview);
        }
    }

    private void plotEllipsePoints(int centerX, int centerY, int x, int y, int color,
                                  PixelCanvas canvas, DrawCommand command, boolean isPreview) {
        if (isPreview) {
            setPixelPreview(centerX + x, centerY + y, color, canvas);
            setPixelPreview(centerX - x, centerY + y, color, canvas);
            setPixelPreview(centerX + x, centerY - y, color, canvas);
            setPixelPreview(centerX - x, centerY - y, color, canvas);
        } else {
            setPixelWithUndo(centerX + x, centerY + y, color, canvas, command);
            setPixelWithUndo(centerX - x, centerY + y, color, canvas, command);
            setPixelWithUndo(centerX + x, centerY - y, color, canvas, command);
            setPixelWithUndo(centerX - x, centerY - y, color, canvas, command);
        }
    }

    private void fillEllipseSpans(int centerX, int centerY, int x, int y, int color,
                                 PixelCanvas canvas, DrawCommand command, boolean isPreview) {
        // Fill horizontal spans at +y and -y
        for (int fillX = centerX - x; fillX <= centerX + x; fillX++) {
            if (isPreview) {
                setPixelPreview(fillX, centerY + y, color, canvas);
                setPixelPreview(fillX, centerY - y, color, canvas);
            } else {
                setPixelWithUndo(fillX, centerY + y, color, canvas, command);
                setPixelWithUndo(fillX, centerY - y, color, canvas, command);
            }
        }
    }

    private void drawTriangle(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas,
                             DrawCommand command, boolean filled, boolean isPreview) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1);
        int maxY = Math.max(y0, y1);

        int apexX = (minX + maxX) / 2;

        if (filled) {
            for (int y = minY; y <= maxY; y++) {
                float progress = (maxY == minY) ? 0 : (float)(y - minY) / (maxY - minY);
                int leftX = Math.round(apexX + progress * (minX - apexX));
                int rightX = Math.round(apexX + progress * (maxX - apexX));

                for (int x = leftX; x <= rightX; x++) {
                    if (isPreview) {
                        setPixelPreview(x, y, color, canvas);
                    } else {
                        setPixelWithUndo(x, y, color, canvas, command);
                    }
                }
            }
        } else {
            drawLine(apexX, minY, minX, maxY, color, canvas, command, isPreview);
            drawLine(apexX, minY, maxX, maxY, color, canvas, command, isPreview);
            drawLine(minX, maxY, maxX, maxY, color, canvas, command, isPreview);
        }
    }

    private void drawLine(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas,
                         DrawCommand command, boolean isPreview) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            if (isPreview) {
                setPixelPreview(x0, y0, color, canvas);
            } else {
                setPixelWithUndo(x0, y0, color, canvas, command);
            }

            if (x0 == x1 && y0 == y1) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private void setPixelPreview(int x, int y, int color, PixelCanvas canvas) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }
        if (!CubeNetValidator.isEditablePixel(x, y, canvas.getWidth(), canvas.getHeight())) {
            return;
        }
        saveOriginalPixel(x, y, canvas);
        canvas.setPixel(x, y, color);
    }

    private void setPixelWithUndo(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }
        if (!CubeNetValidator.isEditablePixel(x, y, canvas.getWidth(), canvas.getHeight())) {
            return;
        }
        int oldColor = canvas.getPixel(x, y);
        if (command != null) {
            command.recordPixelChange(x, y, oldColor, color);
        }
        canvas.setPixel(x, y, color);
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
