package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.MaskSelectionRegion;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionPreview;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionTool;

import java.util.HashSet;
import java.util.Set;

/**
 * Brush-style selection tool that supports painting arbitrary, non-contiguous
 * selections. Integrates with the {@link SelectionManager} so the move tool and
 * other systems can work with the resulting mask-based selection region.
 *
 * Modifier keys (evaluated on stroke start):
 * - ALT: subtract from existing selection
 * - CTRL: replace existing selection with brush stroke
 * - Default: additive (union)
 */
public final class SelectionBrushTool implements SelectionTool {

    private enum StrokeMode {
        ADD,
        SUBTRACT,
        REPLACE
    }

    private final Set<Long> workingPixels = new HashSet<>();
    private final Set<Long> scratchLinePoints = new HashSet<>();

    private SelectionManager selectionManager;

    private StrokeMode nextStrokeMode = StrokeMode.ADD;
    private StrokeMode activeStrokeMode = StrokeMode.ADD;

    private boolean isPainting = false;
    private int lastX;
    private int lastY;

    private SelectionRegion createdSelection;
    private boolean hasSelectionChange;

    public void setSelectionManager(SelectionManager selectionManager) {
        this.selectionManager = selectionManager;
    }

    public void updateModifierState(boolean ctrlHeld, boolean altHeld) {
        if (altHeld) {
            nextStrokeMode = StrokeMode.SUBTRACT;
        } else if (ctrlHeld) {
            nextStrokeMode = StrokeMode.REPLACE;
        } else {
            nextStrokeMode = StrokeMode.ADD;
        }
    }

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        isPainting = true;
        createdSelection = null;
        hasSelectionChange = false;
        activeStrokeMode = nextStrokeMode;

        workingPixels.clear();
        SelectionRegion baseSelection = getActiveSelection(canvas);
        if (activeStrokeMode != StrokeMode.REPLACE) {
            workingPixels.addAll(toEncodedSet(baseSelection));
        }

        if (activeStrokeMode == StrokeMode.REPLACE) {
            workingPixels.clear();
        }

        applyPoint(x, y);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!isPainting) {
            return;
        }
        if (x == lastX && y == lastY) {
            return;
        }

        traceLine(lastX, lastY, x, y);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (!isPainting) {
            return;
        }

        isPainting = false;

        MaskSelectionRegion maskRegion = MaskSelectionRegion.fromEncodedPixels(workingPixels);
        createdSelection = maskRegion.isEmpty() ? null : maskRegion;
        hasSelectionChange = true;
    }

    @Override
    public String getName() {
        return "Selection Brush";
    }

    @Override
    public String getDescription() {
        return "Paint free-form selections (ALT subtracts, CTRL replaces)";
    }

    @Override
    public SelectionRegion getSelection() {
        return createdSelection;
    }

    @Override
    public boolean hasSelection() {
        return hasSelectionChange;
    }

    @Override
    public void clearSelection() {
        hasSelectionChange = false;
    }

    @Override
    public SelectionPreview getPreview() {
        return null; // Preview handled by standard selection overlay after commit
    }

    @Override
    public void reset() {
        isPainting = false;
        createdSelection = null;
        hasSelectionChange = false;
        workingPixels.clear();
        scratchLinePoints.clear();
    }

    private SelectionRegion getActiveSelection(PixelCanvas canvas) {
        if (selectionManager != null) {
            return selectionManager.getActiveSelection();
        }
        return canvas.getActiveSelection();
    }

    private void applyPoint(int x, int y) {
        long encoded = MaskSelectionRegion.encode(x, y);
        switch (activeStrokeMode) {
            case SUBTRACT:
                workingPixels.remove(encoded);
                break;
            case ADD:
            case REPLACE:
            default:
                workingPixels.add(encoded);
                break;
        }
    }

    private void traceLine(int x0, int y0, int x1, int y1) {
        scratchLinePoints.clear();

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int currentX = x0;
        int currentY = y0;

        while (true) {
            scratchLinePoints.add(MaskSelectionRegion.encode(currentX, currentY));
            if (currentX == x1 && currentY == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                currentX += sx;
            }
            if (e2 < dx) {
                err += dx;
                currentY += sy;
            }
        }

        for (long encoded : scratchLinePoints) {
            applyPoint(MaskSelectionRegion.decodeX(encoded), MaskSelectionRegion.decodeY(encoded));
        }
    }

    private static Set<Long> toEncodedSet(SelectionRegion selection) {
        Set<Long> encoded = new HashSet<>();
        if (selection == null || selection.isEmpty()) {
            return encoded;
        }

        java.awt.Rectangle bounds = selection.getBounds();
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (selection.contains(x, y)) {
                    encoded.add(MaskSelectionRegion.encode(x, y));
                }
            }
        }
        return encoded;
    }
}
