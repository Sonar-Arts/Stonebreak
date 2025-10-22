package com.openmason.ui.components.textureCreator;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.io.TextureExporter;
import com.openmason.ui.components.textureCreator.io.TextureImporter;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
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
    private final LayerManager layerManager;
    private final CommandHistory commandHistory;
    private final TextureExporter exporter;
    private final TextureImporter importer;

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

        logger.info("Texture creator controller initialized: {}", size.getDisplayName());
    }

    /**
     * Create a new texture with specified dimensions.
     *
     * @param canvasSize canvas size
     */
    public void newTexture(TextureCreatorState.CanvasSize canvasSize) {
        state.setCurrentCanvasSize(canvasSize);
        state.setCurrentFilePath(null);
        state.setUnsavedChanges(false);

        // TODO: Warn if unsaved changes

        // Create new layer manager
        LayerManager newManager = new LayerManager(canvasSize.getWidth(), canvasSize.getHeight());

        // Clear history
        commandHistory.clear();

        logger.info("Created new texture: {}", canvasSize.getDisplayName());
    }

    /**
     * Export texture to PNG file.
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
            state.setCurrentFilePath(filePath);
            state.markAsSaved();
            logger.info("Exported texture to: {}", filePath);
        }

        return success;
    }

    /**
     * Import texture from PNG file.
     *
     * @param filePath input file path
     * @return true if import succeeded
     */
    public boolean importTexture(String filePath) {
        TextureCreatorState.CanvasSize size = state.getCurrentCanvasSize();

        // Import and resize to current canvas size
        PixelCanvas imported = importer.importFromPNGResized(filePath, size.getWidth(), size.getHeight());

        if (imported == null) {
            logger.error("Failed to import texture from: {}", filePath);
            return false;
        }

        // Create new layer with imported content
        Layer importedLayer = new Layer("Imported", size.getWidth(), size.getHeight());
        importedLayer.getCanvas().copyFrom(imported);

        layerManager.addLayerAt(layerManager.getLayerCount(), importedLayer);

        state.setCurrentFilePath(filePath);
        state.markAsModified();

        logger.info("Imported texture from: {}", filePath);
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
     * Notify that the active layer has been modified.
     * This marks the composite cache as dirty so it will be regenerated.
     */
    public void notifyLayerModified() {
        layerManager.markCompositeDirty();
        state.markAsModified();
    }
}
