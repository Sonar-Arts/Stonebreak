package com.openmason.ui.components.textureCreator.coordinators;

import com.openmason.ui.components.textureCreator.TextureCreatorController;
import com.openmason.ui.components.textureCreator.TextureCreatorState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.filters.LayerFilter;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator for applying filters to layers.
 * Handles filter application with proper undo/redo support and selection awareness.
 *
 * Design Principles:
 * - SOLID: Single responsibility for filter operations
 * - DRY: Centralized filter application logic
 * - KISS: Simple, focused interface
 */
public class FilterCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(FilterCoordinator.class);

    private final TextureCreatorController controller;
    private final TextureCreatorState state;
    private final LayerManager layerManager;
    private final CommandHistory commandHistory;

    /**
     * Create filter coordinator.
     *
     * @param controller Texture creator controller
     * @param state Texture creator state
     * @param layerManager Layer manager
     * @param commandHistory Command history for undo/redo
     */
    public FilterCoordinator(TextureCreatorController controller,
                            TextureCreatorState state,
                            LayerManager layerManager,
                            CommandHistory commandHistory) {
        this.controller = controller;
        this.state = state;
        this.layerManager = layerManager;
        this.commandHistory = commandHistory;
    }

    /**
     * Apply a filter to the active layer.
     * Respects selection regions and integrates with undo/redo system.
     *
     * @param filter The filter to apply
     * @return true if filter was applied successfully, false otherwise
     */
    public boolean applyFilterToActiveLayer(LayerFilter filter) {
        Layer activeLayer = layerManager.getActiveLayer();
        if (activeLayer == null) {
            logger.warn("Cannot apply filter: no active layer");
            return false;
        }

        // Get selection region (may be null)
        SelectionRegion selection = state.getCurrentSelection();

        logger.info("Applying filter '{}' to layer '{}'{}",
                   filter.getName(),
                   activeLayer.getName(),
                   selection != null ? " (within selection)" : "");

        // Get canvas
        PixelCanvas canvas = activeLayer.getCanvas();

        // Create command for undo/redo
        DrawCommand command = new DrawCommand(canvas, "Apply " + filter.getName());

        // Record original state
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // Record pixels that will be affected
        if (selection != null) {
            // Only record pixels within selection
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (selection.contains(x, y)) {
                        int originalColor = canvas.getPixel(x, y);
                        command.recordPixelChange(x, y, originalColor, originalColor); // Will be updated
                    }
                }
            }
        } else {
            // Record all pixels
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int originalColor = canvas.getPixel(x, y);
                    command.recordPixelChange(x, y, originalColor, originalColor); // Will be updated
                }
            }
        }

        try {
            // Apply the filter
            filter.apply(canvas, selection);

            // Update command with new pixel values
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (selection == null || selection.contains(x, y)) {
                        int newColor = canvas.getPixel(x, y);
                        command.updatePixelNewColor(x, y, newColor);
                    }
                }
            }

            // Add to command history
            commandHistory.executeCommand(command);

            // Mark composite dirty for re-rendering
            layerManager.markCompositeDirty();

            logger.info("Filter '{}' applied successfully", filter.getName());
            return true;

        } catch (Exception e) {
            logger.error("Error applying filter '{}': {}", filter.getName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if a filter can be applied (requires an active layer).
     *
     * @return true if a filter can be applied, false otherwise
     */
    public boolean canApplyFilter() {
        return layerManager.getActiveLayer() != null;
    }
}
