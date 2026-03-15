package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.canvas.CanvasShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.CanvasState;
import com.openmason.main.systems.menus.textureCreator.canvas.CompositeShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.CubeNetShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.main.systems.menus.textureCreator.clipboard.ClipboardManager;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.io.TextureExporter;
import com.openmason.main.systems.menus.textureCreator.io.TextureImporter;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Texture creator controller - coordinates all texture creator operations.
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

    // Face-region editing state
    private boolean faceRegionActive = false;
    private int faceRegionMaterialId = -1;
    private UVRegion faceRegionUV;
    private CanvasShapeMask faceRegionMask;
    private CanvasShapeMask canvasBaseMask; // Base mask before face-region editing (restored on close)

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

        // Auto-assign shape mask for known layouts (e.g., 64x48 cube net)
        applyAutoShapeMask(canvasSize.getWidth(), canvasSize.getHeight());

        // Clear command history
        commandHistory.clear();

        // Reset canvas view
        canvasState.resetView();

        logger.info("Created new texture: {}", canvasSize.getDisplayName());
    }

    /**
     * Create a new blank canvas with arbitrary dimensions and an opaque fill color.
     * Used when opening a face for editing that has no existing texture —
     * the canvas must match the GPU texture so the preview pipeline uploads correct data.
     *
     * @param width     canvas width in pixels
     * @param height    canvas height in pixels
     * @param fillColor RGBA fill color (use {@link PixelCanvas#packRGBA})
     */
    public void prepareBlankCanvas(int width, int height, int fillColor) {
        state.setCurrentFilePath(null);
        state.setUnsavedChanges(false);

        this.layerManager = new LayerManager(width, height);
        commandHistory.clear();

        // Auto-assign shape mask for known layouts
        applyAutoShapeMask(width, height);

        // Fill the background layer to match the blank GPU texture
        PixelCanvas canvas = getActiveLayerCanvas();
        if (canvas != null) {
            canvas.fill(fillColor);
        }

        canvasState.resetView();
        logger.info("Prepared blank canvas: {}x{}", width, height);
    }

    /**
     * Prepare the canvas with existing pixel data read back from a GPU texture.
     * Used when opening a face whose material already has texture data (e.g. loaded from .OMO)
     * so the texture editor shows the current pixels instead of a blank canvas.
     *
     * @param width      texture width in pixels
     * @param height     texture height in pixels
     * @param rgbaPixels RGBA byte array (4 bytes per pixel, row-major)
     */
    public void prepareCanvasFromPixels(int width, int height, byte[] rgbaPixels) {
        state.setCurrentFilePath(null);
        state.setUnsavedChanges(false);

        this.layerManager = new LayerManager(width, height);
        commandHistory.clear();

        // Auto-assign shape mask for known layouts
        applyAutoShapeMask(width, height);

        PixelCanvas canvas = getActiveLayerCanvas();
        if (canvas != null && rgbaPixels != null) {
            int[] pixels = canvas.getPixels();
            int pixelCount = Math.min(pixels.length, rgbaPixels.length / 4);
            for (int i = 0; i < pixelCount; i++) {
                int offset = i * 4;
                int r = rgbaPixels[offset] & 0xFF;
                int g = rgbaPixels[offset + 1] & 0xFF;
                int b = rgbaPixels[offset + 2] & 0xFF;
                int a = rgbaPixels[offset + 3] & 0xFF;
                pixels[i] = PixelCanvas.packRGBA(r, g, b, a);
            }
        }

        canvasState.resetView();
        logger.info("Prepared canvas from existing texture: {}x{}", width, height);
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

        // Auto-assign shape mask for known layouts
        applyAutoShapeMask(targetSize.getWidth(), targetSize.getHeight());

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
        DrawCommand deleteCommand =
            new DrawCommand(canvas, "Delete Selection");

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

    // ── Face-region editing ──────────────────────────────────────────────

    /**
     * Open the texture editor focused on a specific face's UV region.
     *
     * <p>Sets up face-region editing mode: applies the shape mask to the
     * active layer's canvas (composing with any existing base mask),
     * frames the view on the UV region, and records the material context
     * for real-time preview integration.
     *
     * @param materialId     material ID the face belongs to
     * @param uvRegion       the face's UV region within the material texture
     * @param mask           shape mask defining the paintable area (nullable for full-rect faces)
     * @param viewportWidth  available viewport width in screen pixels
     * @param viewportHeight available viewport height in screen pixels
     */
    public void openFaceRegion(int materialId, UVRegion uvRegion, CanvasShapeMask mask,
                                float viewportWidth, float viewportHeight) {
        this.faceRegionActive = true;
        this.faceRegionMaterialId = materialId;
        this.faceRegionUV = uvRegion;
        this.faceRegionMask = mask;

        // Apply the face mask to the active layer canvas, composing with
        // any existing base mask (e.g., cube net mask)
        PixelCanvas activeCanvas = getActiveLayerCanvas();
        if (activeCanvas != null) {
            this.canvasBaseMask = activeCanvas.getShapeMask();

            if (canvasBaseMask != null && mask != null) {
                activeCanvas.setShapeMask(new CompositeShapeMask(canvasBaseMask, mask));
            } else {
                activeCanvas.setShapeMask(mask != null ? mask : canvasBaseMask);
            }
        }

        // Frame the view on the face's UV region
        int canvasWidth = layerManager.getCanvasWidth();
        int canvasHeight = layerManager.getCanvasHeight();
        canvasState.frameTo(uvRegion.u0(), uvRegion.v0(), uvRegion.u1(), uvRegion.v1(),
                            canvasWidth, canvasHeight, viewportWidth, viewportHeight);

        // Clear non-editable pixels to transparent so the base texture
        // doesn't bleed into non-paintable areas on the canvas
        if (activeCanvas != null) {
            CanvasShapeMask effectiveMask = activeCanvas.getShapeMask();
            if (effectiveMask != null) {
                int[] pixels = activeCanvas.getPixels();
                int w = activeCanvas.getWidth();
                int h = activeCanvas.getHeight();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (!effectiveMask.isEditable(x, y)) {
                            pixels[y * w + x] = 0x00000000;
                        }
                    }
                }
            }
        }

        // Force a full GPU upload so the preview pipeline pushes the canvas
        // contents to the new target texture on the next flush
        if (activeCanvas != null) {
            activeCanvas.notifyFullCanvasDirty();
        }

        logger.info("Opened face region: materialId={}, uv=({},{})→({},{}), mask={}",
            materialId, uvRegion.u0(), uvRegion.v0(), uvRegion.u1(), uvRegion.v1(),
            mask != null ? "active" : "none");
    }

    /**
     * Close face-region editing mode, restoring the base canvas mask.
     */
    public void closeFaceRegion() {
        // Restore the base mask (e.g., cube net mask) that was active before face-region mode
        PixelCanvas activeCanvas = getActiveLayerCanvas();
        if (activeCanvas != null) {
            activeCanvas.setShapeMask(canvasBaseMask);
        }

        this.faceRegionActive = false;
        this.faceRegionMaterialId = -1;
        this.faceRegionUV = null;
        this.faceRegionMask = null;
        this.canvasBaseMask = null;

        // Reset view to show full canvas
        canvasState.resetView();

        logger.info("Closed face region editing");
    }

    /**
     * Check if face-region editing mode is active.
     *
     * @return true if editing a specific face region
     */
    public boolean isFaceRegionActive() {
        return faceRegionActive;
    }

    /**
     * Get the material ID of the face being edited.
     *
     * @return material ID, or -1 if not in face-region mode
     */
    public int getFaceRegionMaterialId() {
        return faceRegionMaterialId;
    }

    /**
     * Get the UV region of the face being edited.
     *
     * @return UV region, or null if not in face-region mode
     */
    public UVRegion getFaceRegionUV() {
        return faceRegionUV;
    }

    // ── Shape mask auto-assignment ──────────────────────────────────────

    /**
     * Apply an automatic shape mask based on canvas dimensions.
     *
     * <p>Currently recognizes:
     * <ul>
     *   <li>64x48 — standard cube net layout ({@link CubeNetShapeMask})</li>
     * </ul>
     *
     * <p>For unrecognized dimensions, no mask is applied (all pixels editable).
     *
     * @param width  canvas width in pixels
     * @param height canvas height in pixels
     */
    private void applyAutoShapeMask(int width, int height) {
        CanvasShapeMask autoMask = CubeNetShapeMask.createIfApplicable(width, height);
        PixelCanvas canvas = getActiveLayerCanvas();
        if (canvas != null) {
            canvas.setShapeMask(autoMask);
        }
    }
}
