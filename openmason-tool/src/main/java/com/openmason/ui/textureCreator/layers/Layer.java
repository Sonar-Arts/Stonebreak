package com.openmason.ui.textureCreator.layers;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;

/**
 * Immutable layer data model.
 */
public class Layer {

    private final String name;
    private final PixelCanvas canvas;
    private final boolean visible;
    private final float opacity; // 0.0 to 1.0

    /**
     * Create a new layer.
     */
    public Layer(String name, PixelCanvas canvas, boolean visible, float opacity) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Layer name cannot be null or empty");
        }
        if (canvas == null) {
            throw new IllegalArgumentException("Layer canvas cannot be null");
        }
        if (opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("Opacity must be between 0.0 and 1.0: " + opacity);
        }

        this.name = name;
        this.canvas = canvas;
        this.visible = visible;
        this.opacity = opacity;
    }

    /**
     * Create a new layer with default settings.
     */
    public Layer(String name, int width, int height) {
        this(name, new PixelCanvas(width, height), true, 1.0f);
    }

    public String getName() {
        return name;
    }

    public PixelCanvas getCanvas() {
        return canvas;
    }

    public boolean isVisible() {
        return visible;
    }

    public float getOpacity() {
        return opacity;
    }

    public Layer withName(String newName) {
        return new Layer(newName, canvas, visible, opacity);
    }

    public Layer withVisibility(boolean newVisible) {
        return new Layer(name, canvas, newVisible, opacity);
    }

    public Layer withOpacity(float newOpacity) {
        return new Layer(name, canvas, visible, newOpacity);
    }

    public Layer copy() {
        PixelCanvas canvasCopy = canvas.copy();
        return new Layer(name + " (Copy)", canvasCopy, visible, opacity);
    }

    public Layer copy(String newName) {
        PixelCanvas canvasCopy = canvas.copy();
        return new Layer(newName, canvasCopy, visible, opacity);
    }

    @Override
    public String toString() {
        return String.format("Layer[name=%s, visible=%b, opacity=%.2f, size=%dx%d]",
                           name, visible, opacity, canvas.getWidth(), canvas.getHeight());
    }
}
