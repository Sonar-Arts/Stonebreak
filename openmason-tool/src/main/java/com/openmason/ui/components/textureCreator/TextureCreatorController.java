package com.openmason.ui.components.textureCreator;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.clipboard.ClipboardManager;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.io.TextureExporter;
import com.openmason.ui.components.textureCreator.io.TextureImporter;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Texture creator controller - coordinates all texture creator operations.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Coordinates business logic only
 * - Dependency Inversion: Depends on abstractions (interfaces)
 *
 * @author Open Mason Team
 */
public class TextureCreatorController {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorController.class);

    private final TextureCreatorState state;
    private final CanvasState canvasState;
    private LayerManager layerManager; // Not final - can be replaced when creating new texture
    private final CommandHistory commandHistory;
    private final TextureExporter exporter;
    private final TextureImporter importer;
    private final ClipboardManager clipboard;

    /**
     * Create texture creator controller.
     *
     * @param state texture creator state
     */
    public TextureCreatorController(TextureCreatorState state) {
        this.state = state;
        this.canvasState = new CanvasState();

        // Initialize canvas size
        TextureCreatorState.CanvasSize size = state.getCurrentCanvasSize();
        this.layerManager = new LayerManager(size.getWidth(), size.getHeight());

        this.commandHistory = new CommandHistory();
        this.exporter = new TextureExporter();
        this.importer = new TextureImporter();
        this.clipboard = new ClipboardManager();

        logger.info("Texture creator controller initialized: {}", size.getDisplayName());
    }

    /**
     * Create a new texture with specified dimensions.
     *
     * @param canvasSize canvas size
     */
    public void newTexture(TextureCreatorState.CanvasSize canvasSize) {
        // TODO: Warn if unsaved changes

        // Update state
        state.setCurrentCanvasSize(canvasSize);
        state.setCurrentFilePath(null);
        state.setUnsavedChanges(false);

        // Create new layer manager with correct canvas size
        // This automatically creates a default "Background" layer
        this.layerManager = new LayerManager(canvasSize.getWidth(), canvasSize.getHeight());

        // Clear command history
        commandHistory.clear();

        // Reset canvas view
        canvasState.resetView();

        logger.info("Created new texture: {}", canvasSize.getDisplayName());
    }

    /**
     * Save project to .OMT file (preserves all layers and project state).
     *
     * @param filePath output file path
     * @return true if save succeeded
     */
    public boolean saveProject(String filePath) {
        boolean success = exporter.exportToOMT(layerManager, filePath);

        if (success) {
            state.setCurrentFilePath(filePath);
            state.setIsProjectFile(true);
            state.markAsSaved();
            logger.info("Saved project to: {}", filePath);
        }

        return success;
    }

    /**
     * Load project from .OMT file (restores all layers and project state).
     *
     * @param filePath input file path
     * @return true if load succeeded
     */
    public boolean loadProject(String filePath) {
        LayerManager loadedManager = importer.importFromOMT(filePath);

        if (loadedManager == null) {
            logger.error("Failed to load project from: {}", filePath);
            return false;
        }

        // Replace the entire layer manager with the loaded one
        this.layerManager = loadedManager;

        // Update state
        state.setCurrentFilePath(filePath);
        state.setIsProjectFile(true);
        state.markAsSaved();

        // Clear command history since we're loading a new project
        commandHistory.clear();

        logger.info("Loaded project from: {} with {} layers", filePath, loadedManager.getLayerCount());
        return true;
    }

    /**
     * Export texture to PNG file (flattens all visible layers).
     *
     * @param filePath output file path
     * @return true if export succeeded
     */
    public boolean exportTexture(String filePath) {
        // Composite all layers
        PixelCanvas composite = layerManager.compositeLayersToCanvas();

        // Export to PNG
        boolean success = exporter.exportToPNG(composite, filePath);

        if (success) {
            // Note: We DON'T update currentFilePath or mark as saved for exports
            // Exports are separate from the project file
            logger.info("Exported texture to: {}", filePath);
        }

        return success;
    }

    /**
     * Import texture from PNG file with specified target canvas size.
     * Creates a new canvas (replaces current) with the target size.
     *
     * @param filePath input file path
     * @param targetSize target canvas size (16x16 or 64x48)
     * @return true if import succeeded
     */
    public boolean importTexture(String filePath, TextureCreatorState.CanvasSize targetSize) {
        // Import and resize to target canvas size
        PixelCanvas imported = importer.importFromPNGResized(filePath, targetSize.getWidth(), targetSize.getHeight());

        if (imported == null) {
            logger.error("Failed to import texture from: {}", filePath);
            return false;
        }

        // Update state with new canvas size
        state.setCurrentCanvasSize(targetSize);
        state.setCurrentFilePath(filePath);
        state.setIsProjectFile(false); // PNG import is not a project file
        state.markAsModified();

        // Create new layer manager with target canvas size
        // This replaces the entire canvas (similar to newTexture)
        this.layerManager = new LayerManager(targetSize.getWidth(), targetSize.getHeight());

        // Replace the default "Background" layer with imported content
        layerManager.renameLayer(0, "Imported");
        Layer backgroundLayer = layerManager.getLayer(0);
        backgroundLayer.getCanvas().copyFrom(imported);

        // Clear command history
        commandHistory.clear();

        // Reset canvas view
        canvasState.resetView();

        logger.info("Imported texture from: {} to {} canvas", filePath, targetSize.getDisplayName());
        return true;
    }

    /**
     * Undo last operation.
     */
    public void undo() {
        if (commandHistory.undo()) {
            layerManager.markCompositeDirty();
            state.markAsModified();
            logger.debug("Undo performed");
        }
    }

    /**
     * Redo last undone operation.
     */
    public void redo() {
        if (commandHistory.redo()) {
            layerManager.markCompositeDirty();
            state.markAsModified();
            logger.debug("Redo performed");
        }
    }

    /**
     * Get the canvas state.
     * @return canvas state
     */
    public CanvasState getCanvasState() {
        return canvasState;
    }

    /**
     * Get the layer manager.
     * @return layer manager
     */
    public LayerManager getLayerManager() {
        return layerManager;
    }

    /**
     * Get the command history.
     * @return command history
     */
    public CommandHistory getCommandHistory() {
        return commandHistory;
    }

    /**
     * Get the texture creator state.
     * @return state
     */
    public TextureCreatorState getState() {
        return state;
    }

    /**
     * Get the texture exporter.
     * @return exporter
     */
    public TextureExporter getExporter() {
        return exporter;
    }

    /**
     * Get the texture importer.
     * @return importer
     */
    public TextureImporter getImporter() {
        return importer;
    }

    /**
     * Get composited canvas (all visible layers merged).
     * @return composited canvas
     */
    public PixelCanvas getCompositedCanvas() {
        return layerManager.compositeLayersToCanvas();
    }

    /**
     * Get active layer canvas.
     * @return active layer canvas, or null if no active layer
     */
    public PixelCanvas getActiveLayerCanvas() {
        Layer activeLayer = layerManager.getActiveLayer();
        return activeLayer != null ? activeLayer.getCanvas() : null;
    }

    /**
     * Get composited canvas of all layers EXCEPT the active layer.
     * Useful for multi-layer preview during transformations.
     * @return background canvas (all layers except active), or null if no active layer
     */
    public PixelCanvas getBackgroundCanvas() {
        Layer activeLayer = layerManager.getActiveLayer();
        if (activeLayer == null) {
            return null;
        }
        return layerManager.compositeLayersExcluding(activeLayer);
    }

    /**
     * Notify that the active layer has been modified.
     * This marks the composite cache as dirty so it will be regenerated.
     */
    public void notifyLayerModified() {
        layerManager.markCompositeDirty();
        state.markAsModified();
    }

    /**
     * Get the clipboard manager.
     * @return clipboard manager
     */
    public ClipboardManager getClipboard() {
        return clipboard;
    }

    /**
     * Copy current selection to clipboard.
     */
    public void copySelection() {
        SelectionRegion selection = state.getCurrentSelection();

        if (selection == null || selection.isEmpty()) {
            logger.debug("No active selection to copy");
            return;
        }

        Layer activeLayer = layerManager.getActiveLayer();
        if (activeLayer == null) {
            logger.warn("No active layer to copy from");
            return;
        }

        PixelCanvas canvas = activeLayer.getCanvas();
        clipboard.copy(canvas, selection);
    }

    /**
     * Cut current selection to clipboard (copy then clear).
     */
    public void cutSelection() {
        SelectionRegion selection = state.getCurrentSelection();

        if (selection == null || selection.isEmpty()) {
            logger.debug("No active selection to cut");
            return;
        }

        Layer activeLayer = layerManager.getActiveLayer();
        if (activeLayer == null) {
            logger.warn("No active layer to cut from");
            return;
        }

        PixelCanvas canvas = activeLayer.getCanvas();

        // Create command for the cut operation
        DrawCommand cutCommand = new DrawCommand(canvas, "Cut Selection");

        // Cut to clipboard (copy + clear)
        clipboard.cut(canvas, selection, cutCommand);

        // Execute command if changes were made
        if (cutCommand.hasChanges()) {
            commandHistory.executeCommand(cutCommand);
            notifyLayerModified();
        }
    }

    /**
     * Check if clipboard has data that can be pasted.
     * @return true if clipboard has data
     */
    public boolean canPaste() {
        return clipboard.hasData();
    }

    /**
     * Delete the contents of the current selection.
     * Sets all pixels within the selection to transparent.
     */
    public void deleteSelection() {
        SelectionRegion selection = state.getCurrentSelection();

        if (selection == null || selection.isEmpty()) {
            logger.debug("No active selection to delete");
            return;
        }

        Layer activeLayer = layerManager.getActiveLayer();
        if (activeLayer == null) {
            logger.warn("No active layer to delete selection from");
            return;
        }

        PixelCanvas canvas = activeLayer.getCanvas();
        java.awt.Rectangle bounds = selection.getBounds();

        // Create command for undo/redo support
        com.openmason.ui.components.textureCreator.commands.DrawCommand deleteCommand =
            new com.openmason.ui.components.textureCreator.commands.DrawCommand(canvas, "Delete Selection");

        // Delete all pixels within selection
        int pixelsDeleted = 0;
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (canvas.isValidCoordinate(x, y) && selection.contains(x, y)) {
                    int oldColor = canvas.getPixel(x, y);
                    int newColor = 0x00000000; // Transparent

                    if (oldColor != newColor) {
                        deleteCommand.recordPixelChange(x, y, oldColor, newColor);
                        pixelsDeleted++;
                    }
                }
            }
        }

        // Only execute if pixels were changed
        if (deleteCommand.hasChanges()) {
            commandHistory.executeCommand(deleteCommand);
            notifyLayerModified();
            logger.info("Deleted {} pixels from selection", pixelsDeleted);
        } else {
            logger.debug("No pixels to delete in selection (all already transparent)");
        }
    }
}
