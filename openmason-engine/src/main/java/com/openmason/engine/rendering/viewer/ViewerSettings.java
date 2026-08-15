package com.openmason.engine.rendering.viewer;

import com.openmason.engine.rendering.viewer.gizmo.SnapSettings;

/**
 * Per-frame display settings for a {@link ModelViewer}.
 *
 * <p>Deliberately a plain mutable POJO with no ImGui types. The tool's
 * {@code ViewportUIState} is built from {@code ImBoolean}/{@code ImFloat}, which cannot
 * come into the engine; a host pushes the handful of values the viewer actually needs
 * into this object once per frame instead.
 */
public class ViewerSettings implements SnapSettings {

    private boolean gridVisible = true;
    private boolean unrendered = false;

    private float clearRed = 0.2f;
    private float clearGreen = 0.2f;
    private float clearBlue = 0.3f;
    private float clearAlpha = 1.0f;

    private boolean snapEnabled = false;
    private float snapIncrement = 0.0f;

    private int width = 800;
    private int height = 600;

    public boolean isGridVisible() { return gridVisible; }
    public void setGridVisible(boolean gridVisible) { this.gridVisible = gridVisible; }

    /** Flat-gray "solid view" mode, matching Blender's shading toggle. */
    public boolean isUnrendered() { return unrendered; }
    public void setUnrendered(boolean unrendered) { this.unrendered = unrendered; }

    public float getClearRed() { return clearRed; }
    public float getClearGreen() { return clearGreen; }
    public float getClearBlue() { return clearBlue; }
    public float getClearAlpha() { return clearAlpha; }

    public void setClearColor(float r, float g, float b, float a) {
        this.clearRed = r;
        this.clearGreen = g;
        this.clearBlue = b;
        this.clearAlpha = a;
    }

    @Override
    public boolean isSnapEnabled() { return snapEnabled; }
    public void setSnapEnabled(boolean snapEnabled) { this.snapEnabled = snapEnabled; }

    @Override
    public float getSnapIncrement() { return snapIncrement; }
    public void setSnapIncrement(float snapIncrement) { this.snapIncrement = snapIncrement; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }
}
