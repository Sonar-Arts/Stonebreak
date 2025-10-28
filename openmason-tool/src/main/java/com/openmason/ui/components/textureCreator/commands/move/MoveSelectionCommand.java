package com.openmason.ui.components.textureCreator.commands.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.Command;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.move.modules.TransformState;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;

/**
 * Command for moving/transforming a selection.
 * Supports undo/redo by storing original and transformed pixel states.
 * Now integrates with SelectionManager for centralized selection state management.
 */
public class MoveSelectionCommand implements Command {

    private final PixelCanvas canvas;
    private final SelectionManager selectionManager;
    private final SelectionRegion originalSelection;
    private final SelectionRegion transformedSelection;
    private final TransformState transform;

    // Store original pixels (from source area) and their positions
    private final Map<Point, Integer> originalPixels;

    // Store transformed pixels (at destination area) and their positions
    private final Map<Point, Integer> transformedPixels;

    // Store pixels that were overwritten at destination (for undo)
    private final Map<Point, Integer> overwrittenPixels;

    // Track if this is the first execution (pixels already in place) or a redo
    private boolean isFirstExecution = true;

    public MoveSelectionCommand(PixelCanvas canvas,
                                SelectionManager selectionManager,
                                SelectionRegion originalSelection,
                                SelectionRegion transformedSelection,
                                TransformState transform,
                                Map<Point, Integer> originalPixels,
                                Map<Point, Integer> transformedPixels) {
        this.canvas = canvas;
        this.selectionManager = selectionManager;
        this.originalSelection = originalSelection;
        this.transformedSelection = transformedSelection;
        this.transform = transform;
        this.originalPixels = new HashMap<>(originalPixels);
        this.transformedPixels = new HashMap<>(transformedPixels);
        this.overwrittenPixels = new HashMap<>();

        // Capture pixels that will be overwritten at destination
        captureOverwrittenPixels();
    }

    private void captureOverwrittenPixels() {
        for (Point point : transformedPixels.keySet()) {
            if (canvas.isValidCoordinate(point.x, point.y)) {
                int existingColor = canvas.getPixel(point.x, point.y);
                overwrittenPixels.put(point, existingColor);
            }
        }
    }

    @Override
    public void execute() {
        System.out.println("[MoveSelectionCommand] execute() called (isFirstExecution=" + isFirstExecution + ")");
        System.out.println("[MoveSelectionCommand] Original pixels count: " + originalPixels.size());
        System.out.println("[MoveSelectionCommand] Transformed pixels count: " + transformedPixels.size());
        System.out.println("[MoveSelectionCommand] Original selection: " + originalSelection.getBounds());
        System.out.println("[MoveSelectionCommand] Transformed selection: " +
                (transformedSelection != null ? transformedSelection.getBounds() : "null"));

        // On first execution, pixels are already at destination from dragging
        // Skip the actual work to avoid clearing pixels in overlapping regions
        if (isFirstExecution) {
            System.out.println("[MoveSelectionCommand] First execution - pixels already in place, skipping");

            // Update selection to transformed position using SelectionManager
            selectionManager.setActiveSelection(transformedSelection);

            isFirstExecution = false;
            return;
        }

        // For redo: clear source and paste destination
        System.out.println("[MoveSelectionCommand] Redo - clearing source and pasting destination");

        // Bypass selection constraint for redo operations
        canvas.setBypassSelectionConstraint(true);
        try {
            clearSourceArea();
            pasteTransformedPixels();
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }

        // Update selection to transformed position using SelectionManager
        selectionManager.setActiveSelection(transformedSelection);
        System.out.println("[MoveSelectionCommand] Updated selection to transformed position: " +
                (transformedSelection != null ? transformedSelection.getBounds() : "null"));

        System.out.println("[MoveSelectionCommand] execute() complete");

        // Note: canvas modification version is automatically incremented by setPixel() calls
    }

    @Override
    public void undo() {
        System.out.println("[MoveSelectionCommand] undo() called");

        // Bypass selection constraint for undo operations
        canvas.setBypassSelectionConstraint(true);
        try {
            // Clear the transformed pixels at destination
            clearTransformedPixels();

            // Restore original pixels at source location
            restoreOriginalPixels();
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }

        // Restore the selection to its original position using SelectionManager
        selectionManager.setActiveSelection(originalSelection);
        System.out.println("[MoveSelectionCommand] Restored selection to original position: " +
                (originalSelection != null ? originalSelection.getBounds() : "null"));

        // Reset first execution flag so next execute (redo) will actually execute
        isFirstExecution = false;

        System.out.println("[MoveSelectionCommand] undo() complete");

        // Note: canvas modification version is automatically incremented by setPixel() calls
    }

    private void clearSourceArea() {
        if (originalSelection == null || originalSelection.isEmpty()) {
            return;
        }

        java.awt.Rectangle bounds = originalSelection.getBounds();

        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (originalSelection.contains(x, y) && canvas.isValidCoordinate(x, y)) {
                    canvas.setPixel(x, y, 0x00000000); // Transparent
                }
            }
        }
    }

    private void clearTransformedPixels() {
        for (Point point : transformedPixels.keySet()) {
            if (canvas.isValidCoordinate(point.x, point.y)) {
                canvas.setPixel(point.x, point.y, 0x00000000); // Transparent
            }
        }
    }

    private void pasteTransformedPixels() {
        for (Map.Entry<Point, Integer> entry : transformedPixels.entrySet()) {
            Point point = entry.getKey();
            int color = entry.getValue();

            if (canvas.isValidCoordinate(point.x, point.y)) {
                canvas.setPixel(point.x, point.y, color);
            }
        }
    }

    private void restoreOverwrittenPixels() {
        for (Map.Entry<Point, Integer> entry : overwrittenPixels.entrySet()) {
            Point point = entry.getKey();
            int color = entry.getValue();

            if (canvas.isValidCoordinate(point.x, point.y)) {
                canvas.setPixel(point.x, point.y, color);
            }
        }
    }

    private void restoreOriginalPixels() {
        for (Map.Entry<Point, Integer> entry : originalPixels.entrySet()) {
            Point point = entry.getKey();
            int color = entry.getValue();

            if (canvas.isValidCoordinate(point.x, point.y)) {
                canvas.setPixel(point.x, point.y, color);
            }
        }
    }

    @Override
    public String getDescription() {
        if (transform.isIdentity()) {
            return "Move Selection";
        }

        StringBuilder desc = new StringBuilder("Transform Selection: ");
        boolean hasChanges = false;

        if (transform.getTranslateX() != 0 || transform.getTranslateY() != 0) {
            desc.append(String.format("move (%d, %d)", transform.getTranslateX(), transform.getTranslateY()));
            hasChanges = true;
        }

        if (transform.getScaleX() != 1.0 || transform.getScaleY() != 1.0) {
            if (hasChanges) desc.append(", ");
            desc.append(String.format("scale (%.2f, %.2f)", transform.getScaleX(), transform.getScaleY()));
            hasChanges = true;
        }

        if (transform.getRotationDegrees() != 0.0) {
            if (hasChanges) desc.append(", ");
            desc.append(String.format("rotate %.1f°", transform.getRotationDegrees()));
        }

        return desc.toString();
    }

    public boolean hasChanges() {
        return !transformedPixels.isEmpty() || !originalPixels.isEmpty();
    }

    public SelectionRegion getTransformedSelection() {
        return transformedSelection;
    }

    public TransformState getTransform() {
        return transform;
    }

    @Override
    public String toString() {
        return getDescription();
    }
}
