package com.openmason.ui.textureCreator.layers;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;

/**
 * Immutable layer data model.
 *
 * Each layer contains its own pixel data, visibility state, and opacity.
 * Follows SOLID principles - Single Responsibility: represents a single layer.
 *
 * @author Open Mason Team
 */
public class Layer {

    private final String name;
    private final PixelCanvas canvas;
    private final boolean visible;
    private final float opacity; // 0.0 to 1.0

    /**
     * Create a new layer.
     *
     * @param name layer name
     * @param canvas pixel canvas for this layer
     * @param visible visibility state
     * @param opacity opacity level (0.0 = transparent, 1.0 = opaque)
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
     *
     * @param name layer name
     * @param width canvas width
     * @param height canvas height
     */
    public Layer(String name, int width, int height) {
        this(name, new PixelCanvas(width, height), true, 1.0f);
    }

    /**
     * Get layer name.
     * @return layer name
     */
    public String getName() {
        return name;
    }

    /**
     * Get layer canvas.
     * @return pixel canvas
     */
    public PixelCanvas getCanvas() {
        return canvas;
    }

    /**
     * Check if layer is visible.
     * @return true if visible
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Get layer opacity.
     * @return opacity (0.0 to 1.0)
     */
    public float getOpacity() {
        return opacity;
    }

    /**
     * Create a copy of this layer with a new name.
     *
     * @param newName new layer name
     * @return new layer instance
     */
    public Layer withName(String newName) {
        return new Layer(newName, canvas, visible, opacity);
    }

    /**
     * Create a copy of this layer with modified visibility.
     *
     * @param newVisible new visibility state
     * @return new layer instance
     */
    public Layer withVisibility(boolean newVisible) {
        return new Layer(name, canvas, newVisible, opacity);
    }

    /**
     * Create a copy of this layer with modified opacity.
     *
     * @param newOpacity new opacity level
     * @return new layer instance
     */
    public Layer withOpacity(float newOpacity) {
        return new Layer(name, canvas, visible, newOpacity);
    }

    /**
     * Create a deep copy of this layer.
     *
     * @return new layer with copied pixel data
     */
    public Layer copy() {
        PixelCanvas canvasCopy = canvas.copy();
        return new Layer(name + " (Copy)", canvasCopy, visible, opacity);
    }

    /**
     * Create a deep copy of this layer with a custom name.
     *
     * @param newName name for the copied layer
     * @return new layer with copied pixel data
     */
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
