package com.openmason.main.systems.scene;

import imgui.type.ImBoolean;
import imgui.type.ImFloat;

/**
 * Display state for the Scene Viewer window.
 *
 * <p>Separate from the model editor's {@code ViewportUIState} on purpose: the two 3D
 * surfaces have independent grids, snapping and focus, and sharing one state object is
 * exactly how the editor's own state ended up triplicated and out of sync.
 */
public class SceneViewerUIState {

    private final ImBoolean gridVisible = new ImBoolean(true);
    private final ImBoolean axesVisible = new ImBoolean(true);
    private final ImBoolean gridSnappingEnabled = new ImBoolean(false);
    private final ImFloat gridSnappingIncrement = new ImFloat(0.25f);

    private int width = 800;
    private int height = 600;
    private boolean sceneViewFocused;

    public ImBoolean getGridVisible() { return gridVisible; }
    public ImBoolean getAxesVisible() { return axesVisible; }
    public ImBoolean getGridSnappingEnabled() { return gridSnappingEnabled; }
    public ImFloat getGridSnappingIncrement() { return gridSnappingIncrement; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public boolean dimensionsChanged(int newWidth, int newHeight) {
        return newWidth != width || newHeight != height;
    }

    public void setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** Whether the Scene Viewer has keyboard focus, so shortcuts do not fire in both surfaces. */
    public boolean isSceneViewFocused() { return sceneViewFocused; }
    public void setSceneViewFocused(boolean focused) { this.sceneViewFocused = focused; }
}
