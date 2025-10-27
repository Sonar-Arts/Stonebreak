package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.selection.FreeSelectionStrategy;
import com.openmason.ui.components.textureCreator.tools.selection.PixelPreview;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionPreview;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionToolController;

/**
 * Free-form selection tool - creates selections by painting with the mouse.
 * Click and drag to paint individual pixels into the selection.
 *
 * Architecture:
 * - Controller: SelectionToolController (coordinates tool lifecycle)
 * - Strategy: FreeSelectionStrategy (implements free-form paint logic)
 *
 * SOLID: Single responsibility - delegates to controller and strategy
 * KISS: Simple wrapper around controller
 * DRY: Reuses common selection logic from controller
 *
 * @author Open Mason Team
 */
public class FreeSelectionTool implements DrawingTool {

    // Delegate to controller with free selection strategy
    private final SelectionToolController controller;
    private final FreeSelectionStrategy strategy;

    /**
     * Creates a free selection tool with default brush size (1 pixel).
     */
    public FreeSelectionTool() {
        this(1);
    }

    /**
     * Creates a free selection tool with specified brush size.
     *
     * @param brushSize Size of brush in pixels (minimum 1)
     */
    public FreeSelectionTool(int brushSize) {
        this.strategy = new FreeSelectionStrategy(brushSize);
        this.controller = new SelectionToolController(strategy);
    }

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        controller.onMouseDown(x, y, color, canvas, command);
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        controller.onMouseDrag(x, y, color, canvas, command);
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        controller.onMouseUp(color, canvas, command);
    }

    /**
     * Get the pixel preview for rendering.
     * Used by CanvasPanel to render selection preview during drag.
     *
     * @return PixelPreview with selected pixels, or null if not selecting
     */
    public PixelPreview getPixelPreview() {
        SelectionPreview preview = controller.getPreview();
        if (preview instanceof PixelPreview) {
            return (PixelPreview) preview;
        }
        return null;
    }

    /**
     * Get the created selection region.
     * CanvasPanel should read this after onMouseUp to update state.
     *
     * @return The created selection, or null if no selection was created
     */
    public SelectionRegion getCreatedSelection() {
        return controller.getCreatedSelection();
    }

    /**
     * Check if a selection was created during the last drag operation.
     *
     * @return true if selection state changed
     */
    public boolean wasSelectionCreated() {
        return controller.hasSelection();
    }

    /**
     * Clear the created selection flag.
     * Should be called by CanvasPanel after reading the selection.
     */
    public void clearSelectionCreatedFlag() {
        controller.clearSelectionFlag();
    }

    @Override
    public void reset() {
        controller.reset();
    }

    @Override
    public String getName() {
        return controller.getName();
    }

    @Override
    public String getDescription() {
        return controller.getDescription();
    }

    /**
     * Gets the brush size for this tool.
     *
     * @return Brush size in pixels
     */
    public int getBrushSize() {
        return strategy.getBrushSize();
    }

    /**
     * Sets the brush size for this tool.
     * The brush size will be clamped to a minimum of 1 pixel.
     *
     * @param size New brush size in pixels (minimum 1)
     */
    public void setBrushSize(int size) {
        strategy.setBrushSize(size);
    }
}
