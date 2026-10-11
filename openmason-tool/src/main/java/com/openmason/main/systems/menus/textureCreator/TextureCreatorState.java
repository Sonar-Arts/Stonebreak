package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.panels.status.CanvasHoverInfo;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.DrawingTool;
import imgui.type.ImBoolean;

/**
 * State management for the Texture Creator component. Tool, colour, symmetry, grid and hover state
 * are shared by every open texture; canvas size, file, dirty flag and selection belong to the
 * bound {@link TextureDocument} (the controller binds its active document, so readers always see
 * the texture on screen).
 */
public class TextureCreatorState {

    /**
     * Canvas dimensions specified by the user.
     *
     * @param width  canvas width in pixels
     * @param height canvas height in pixels
     */
    public record CanvasSize(int width, int height) {
        public CanvasSize {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Canvas dimensions must be positive: " + width + "x" + height);
            }
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getDisplayName() { return width + "x" + height; }
    }

    // State fields
    private TextureDocument document; // per-texture state: size, file, dirty flag, selection
    private DrawingTool currentTool;
    private int currentColor; // RGBA packed as int
    private final SymmetryState symmetryState; // Symmetry/mirror mode state
    private final ImBoolean showGrid;
    private final CanvasHoverInfo canvasHoverInfo; // Hovered pixel for status bar

    /**
     * Create new texture creator state with defaults.
     */
    public TextureCreatorState() {
        this.document = new TextureDocument("unbound", "Untitled",
                new com.openmason.main.systems.menus.textureCreator.layers.LayerManager(16, 16));
        this.currentTool = null; // Will be set when tools are initialized
        this.currentColor = 0xFF000000; // Black, full alpha
        this.symmetryState = new SymmetryState(); // Initialize symmetry state
        this.showGrid = new ImBoolean(true);
        this.canvasHoverInfo = new CanvasHoverInfo();
    }

    /** The texture the per-document accessors read and write. */
    public TextureDocument getDocument() {
        return document;
    }

    /** Called by the controller whenever its active document changes. */
    void bindDocument(TextureDocument document) {
        this.document = java.util.Objects.requireNonNull(document, "document");
    }

    /**
     * Get the canvas hover info (hovered pixel coordinates for the status bar).
     */
    public CanvasHoverInfo getCanvasHoverInfo() {
        return canvasHoverInfo;
    }

    // Getters and setters

    public CanvasSize getCurrentCanvasSize() {
        return document.getCanvasSize();
    }

    public void setCurrentCanvasSize(CanvasSize size) {
        document.setCanvasSize(size);
    }

    public DrawingTool getCurrentTool() {
        return currentTool;
    }

    public void setCurrentTool(DrawingTool tool) {
        this.currentTool = tool;
    }

    public int getCurrentColor() {
        return currentColor;
    }

    public void setCurrentColor(int color) {
        this.currentColor = color;
    }

    public String getCurrentFilePath() {
        return document.getFilePath();
    }

    public void setCurrentFilePath(String filePath) {
        document.setFilePath(filePath);
    }

    public void setUnsavedChanges(boolean unsavedChanges) {
        document.setUnsavedChanges(unsavedChanges);
    }

    public ImBoolean getShowGrid() {
        return showGrid;
    }

    /**
     * Check if a file is currently loaded.
     * @return true if a file path is set
     */
    public boolean hasFilePath() {
        String path = document.getFilePath();
        return path != null && !path.trim().isEmpty();
    }

    /** True when the canvas changed since the last save/load. */
    public boolean hasUnsavedChanges() {
        return document.hasUnsavedChanges();
    }

    /**
     * Mark as saved (clears unsaved changes flag).
     */
    public void markAsSaved() {
        document.setUnsavedChanges(false);
    }

    /**
     * Mark as modified (sets unsaved changes flag).
     */
    public void markAsModified() {
        document.setUnsavedChanges(true);
    }

    /**
     * Check if current file is a project file (.OMT).
     * @return true if project file, false if new or PNG
     */
    public boolean isProjectFile() {
        return document.isProjectFile();
    }

    /**
     * Set whether current file is a project file (.OMT).
     * @param isProjectFile true if project file
     */
    public void setIsProjectFile(boolean isProjectFile) {
        document.setProjectFile(isProjectFile);
    }

    /**
     * Get the SelectionManager of the bound document (each texture keeps its own selection).
     * @return The SelectionManager instance
     */
    public SelectionManager getSelectionManager() {
        return document.getSelectionManager();
    }

    /**
     * Get the SymmetryState for this texture creator.
     * @return The SymmetryState instance
     */
    public SymmetryState getSymmetryState() {
        return symmetryState;
    }

    /**
     * Get the current selection region (convenience method).
     * Delegates to SelectionManager.
     * @return The current selection, or null if no selection exists
     */
    public SelectionRegion getCurrentSelection() {
        return document.getSelectionManager().getActiveSelection();
    }

    /**
     * Set the current selection region (convenience method).
     * Delegates to SelectionManager.
     * @param selection The selection region to set (can be null to clear selection)
     */
    public void setCurrentSelection(SelectionRegion selection) {
        document.getSelectionManager().setActiveSelection(selection);
    }

    /**
     * Check if a selection is currently active (convenience method).
     * Delegates to SelectionManager.
     * @return true if there is an active selection, false otherwise
     */
    public boolean hasSelection() {
        return document.getSelectionManager().hasActiveSelection();
    }

    /**
     * Clear the current selection (convenience method).
     * Delegates to SelectionManager.
     */
    public void clearSelection() {
        document.getSelectionManager().clearSelection();
    }
}
