package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.DrawingTool;
import imgui.type.ImBoolean;

/**
 * State management for the Texture Creator component.
 */
public class TextureCreatorState {

    // Canvas dimensions
    public enum CanvasSize {
        SIZE_16x16(16, 16, "16x16 (Block/Item)"),
        SIZE_64x48(64, 48, "Block Texture");

        private final int width;
        private final int height;
        private final String displayName;

        CanvasSize(int width, int height, String displayName) {
            this.width = width;
            this.height = height;
            this.displayName = displayName;
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getDisplayName() { return displayName; }
    }

    // State fields
    private CanvasSize currentCanvasSize;
    private DrawingTool currentTool;
    private int currentColor; // RGBA packed as int
    private String currentFilePath;
    private boolean unsavedChanges;
    private boolean isProjectFile; // true if opened/saved as .OMT, false for new/PNG
    private final SelectionManager selectionManager; // Centralized selection management
    private final SymmetryState symmetryState; // Symmetry/mirror mode state
    private final ImBoolean showGrid;

    /**
     * Create new texture creator state with defaults.
     */
    public TextureCreatorState() {
        this.currentCanvasSize = CanvasSize.SIZE_16x16;
        this.currentTool = null; // Will be set when tools are initialized
        this.currentColor = 0xFF000000; // Black, full alpha
        this.currentFilePath = null;
        this.unsavedChanges = false;
        this.isProjectFile = false;
        this.selectionManager = new SelectionManager(); // Initialize selection manager
        this.symmetryState = new SymmetryState(); // Initialize symmetry state
        this.showGrid = new ImBoolean(true);
    }

    // Getters and setters

    public CanvasSize getCurrentCanvasSize() {
        return currentCanvasSize;
    }

    public void setCurrentCanvasSize(CanvasSize size) {
        this.currentCanvasSize = size;
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
        return currentFilePath;
    }

    public void setCurrentFilePath(String filePath) {
        this.currentFilePath = filePath;
    }

    public void setUnsavedChanges(boolean unsavedChanges) {
        this.unsavedChanges = unsavedChanges;
    }

    public ImBoolean getShowGrid() {
        return showGrid;
    }

    /**
     * Check if a file is currently loaded.
     * @return true if a file path is set
     */
    public boolean hasFilePath() {
        return currentFilePath != null && !currentFilePath.trim().isEmpty();
    }

    /**
     * Mark as saved (clears unsaved changes flag).
     */
    public void markAsSaved() {
        this.unsavedChanges = false;
    }

    /**
     * Mark as modified (sets unsaved changes flag).
     */
    public void markAsModified() {
        this.unsavedChanges = true;
    }

    /**
     * Check if current file is a project file (.OMT).
     * @return true if project file, false if new or PNG
     */
    public boolean isProjectFile() {
        return isProjectFile;
    }

    /**
     * Set whether current file is a project file (.OMT).
     * @param isProjectFile true if project file
     */
    public void setIsProjectFile(boolean isProjectFile) {
        this.isProjectFile = isProjectFile;
    }

    /**
     * Get the SelectionManager for this texture creator.
     * @return The SelectionManager instance
     */
    public SelectionManager getSelectionManager() {
        return selectionManager;
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
        return selectionManager.getActiveSelection();
    }

    /**
     * Set the current selection region (convenience method).
     * Delegates to SelectionManager.
     * @param selection The selection region to set (can be null to clear selection)
     */
    public void setCurrentSelection(SelectionRegion selection) {
        selectionManager.setActiveSelection(selection);
    }

    /**
     * Check if a selection is currently active (convenience method).
     * Delegates to SelectionManager.
     * @return true if there is an active selection, false otherwise
     */
    public boolean hasSelection() {
        return selectionManager.hasActiveSelection();
    }

    /**
     * Clear the current selection (convenience method).
     * Delegates to SelectionManager.
     */
    public void clearSelection() {
        selectionManager.clearSelection();
    }
}
