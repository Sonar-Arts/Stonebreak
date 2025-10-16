package com.openmason.ui.state;

import imgui.type.ImInt;

/**
 * Centralized viewport state management.
 * Follows Single Responsibility Principle - only manages viewport-related state.
 */
public class ViewportState {

    private boolean showGrid = true;
    private boolean showAxes = true;
    private boolean wireframeMode = false;

    // View Mode State
    private final String[] viewModes = {"Perspective", "Orthographic", "Front", "Side", "Top"};
    private final ImInt currentViewModeIndex = new ImInt(0);

    // Render Mode State
    private final String[] renderModes = {"Solid", "Wireframe", "Textured"};
    private final ImInt currentRenderModeIndex = new ImInt(2); // Textured

    // Getters and Setters

    public boolean isShowGrid() {
        return showGrid;
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
    }

    public boolean isShowAxes() {
        return showAxes;
    }

    public void setShowAxes(boolean showAxes) {
        this.showAxes = showAxes;
    }

    public boolean isWireframeMode() {
        return wireframeMode;
    }

    public void setWireframeMode(boolean wireframeMode) {
        this.wireframeMode = wireframeMode;
    }

    public String[] getViewModes() {
        return viewModes;
    }

    public ImInt getCurrentViewModeIndex() {
        return currentViewModeIndex;
    }

    public String getCurrentViewMode() {
        return viewModes[currentViewModeIndex.get()];
    }

    public String[] getRenderModes() {
        return renderModes;
    }

    public ImInt getCurrentRenderModeIndex() {
        return currentRenderModeIndex;
    }

    public String getCurrentRenderMode() {
        return renderModes[currentRenderModeIndex.get()];
    }

    /**
     * Toggle grid visibility.
     */
    public void toggleGrid() {
        showGrid = !showGrid;
    }

    /**
     * Toggle axes visibility.
     */
    public void toggleAxes() {
        showAxes = !showAxes;
    }

    /**
     * Toggle wireframe mode.
     */
    public void toggleWireframe() {
        wireframeMode = !wireframeMode;
    }

    /**
     * Reset to default viewport state.
     */
    public void reset() {
        showGrid = true;
        showAxes = true;
        wireframeMode = false;
        currentViewModeIndex.set(0);
        currentRenderModeIndex.set(2);
    }
}
