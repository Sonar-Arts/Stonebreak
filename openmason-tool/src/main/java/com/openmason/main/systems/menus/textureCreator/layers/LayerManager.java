package com.openmason.main.systems.menus.textureCreator.layers;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Layer manager handles all layer operations.
 */
public class LayerManager {

    private static final Logger logger = LoggerFactory.getLogger(LayerManager.class);

    private final List<Layer> layers;
    private int activeLayerIndex;
    private final int canvasWidth;
    private final int canvasHeight;

    // Compositing cache
    private PixelCanvas compositedCache = null;
    private boolean compositeCacheDirty = true;

    /**
     * Create layer manager with specified canvas dimensions.
     *
     * @param canvasWidth canvas width
     * @param canvasHeight canvas height
     */
    public LayerManager(int canvasWidth, int canvasHeight) {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("Canvas dimensions must be positive");
        }

        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.layers = new ArrayList<>();
        this.activeLayerIndex = -1;

        // Create default layer
        addLayer("Background");
        logger.debug("Layer manager initialized: {}x{}", canvasWidth, canvasHeight);
    }

    /**
     * Add a new layer.
     *
     * @param name layer name
     */
    public void addLayer(String name) {
        Layer newLayer = new Layer(name, canvasWidth, canvasHeight);
        layers.add(newLayer);
        activeLayerIndex = layers.size() - 1; // New layer becomes active
        compositeCacheDirty = true;
        logger.debug("Added layer: {}", name);
    }

    /**
     * Add a layer at specific index.
     *
     * @param index insertion index
     * @param layer layer to add
     */
    public void addLayerAt(int index, Layer layer) {
        if (index < 0 || index > layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }
        layers.add(index, layer);
        activeLayerIndex = index;
        compositeCacheDirty = true;
        logger.debug("Added layer at index {}: {}", index, layer.getName());
    }

    /**
     * Remove layer at index.
     *
     * @param index layer index
     */
    public void removeLayer(int index) {
        if (layers.size() <= 1) {
            throw new IllegalStateException("Cannot remove last layer");
        }
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }

        Layer removed = layers.remove(index);

        // Adjust active layer index
        if (activeLayerIndex >= layers.size()) {
            activeLayerIndex = layers.size() - 1;
        } else if (activeLayerIndex == index && activeLayerIndex > 0) {
            activeLayerIndex--;
        }

        compositeCacheDirty = true;
        logger.debug("Removed layer: {}", removed.getName());
    }

    /**
     * Move layer to a new position.
     *
     * @param fromIndex current index
     * @param toIndex target index
     */
    public void moveLayer(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid from index: " + fromIndex);
        }
        if (toIndex < 0 || toIndex >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid to index: " + toIndex);
        }
        if (fromIndex == toIndex) {
            return; // No move needed
        }

        Layer layer = layers.remove(fromIndex);
        layers.add(toIndex, layer);

        // Update active layer index if it was the moved layer
        if (activeLayerIndex == fromIndex) {
            activeLayerIndex = toIndex;
        }

        compositeCacheDirty = true;
        logger.debug("Moved layer from {} to {}: {}", fromIndex, toIndex, layer.getName());
    }

    /**
     * Duplicate layer at index.
     *
     * @param index layer index to duplicate
     */
    public void duplicateLayer(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }

        Layer original = layers.get(index);
        Layer duplicate = original.copy();
        layers.add(index + 1, duplicate);
        activeLayerIndex = index + 1;

        compositeCacheDirty = true;
        logger.debug("Duplicated layer: {}", original.getName());
    }

    /**
     * Set layer visibility.
     *
     * @param index layer index
     * @param visible visibility state
     */
    public void setLayerVisibility(int index, boolean visible) {
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }

        Layer layer = layers.get(index);
        Layer updated = layer.withVisibility(visible);
        layers.set(index, updated);
        compositeCacheDirty = true;
        logger.debug("Set layer visibility: {} = {}", layer.getName(), visible);
    }

    /**
     * Set layer opacity.
     *
     * @param index layer index
     * @param opacity opacity value (0.0 to 1.0)
     */
    public void setLayerOpacity(int index, float opacity) {
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }

        Layer layer = layers.get(index);
        Layer updated = layer.withOpacity(opacity);
        layers.set(index, updated);
        compositeCacheDirty = true;
        logger.debug("Set layer opacity: {} = {}", layer.getName(), opacity);
    }

    /**
     * Rename layer.
     *
     * @param index layer index
     * @param newName new layer name
     */
    public void renameLayer(int index, String newName) {
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }

        Layer layer = layers.get(index);
        Layer updated = layer.withName(newName);
        layers.set(index, updated);
        logger.debug("Renamed layer: {} -> {}", layer.getName(), newName);
    }

    /**
     * Get all layers.
     * @return unmodifiable list of layers
     */
    public List<Layer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Get layer at index.
     *
     * @param index layer index
     * @return layer
     */
    public Layer getLayer(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }
        return layers.get(index);
    }

    /**
     * Get active layer.
     * @return active layer, or null if no layers
     */
    public Layer getActiveLayer() {
        if (activeLayerIndex < 0 || activeLayerIndex >= layers.size()) {
            return null;
        }
        return layers.get(activeLayerIndex);
    }

    /**
     * Get active layer index.
     * @return active layer index
     */
    public int getActiveLayerIndex() {
        return activeLayerIndex;
    }

    /**
     * Set active layer.
     *
     * @param index layer index to activate
     */
    public void setActiveLayer(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IndexOutOfBoundsException("Invalid layer index: " + index);
        }
        activeLayerIndex = index;
        logger.debug("Set active layer: {} ({})", index, layers.get(index).getName());
    }

    /**
     * Get number of layers.
     * @return layer count
     */
    public int getLayerCount() {
        return layers.size();
    }

    /**
     * Get canvas width.
     * @return canvas width in pixels
     */
    public int getCanvasWidth() {
        return canvasWidth;
    }

    /**
     * Get canvas height.
     * @return canvas height in pixels
     */
    public int getCanvasHeight() {
        return canvasHeight;
    }

    /**
     * Composite all visible layers into a single canvas.
     * Layers are composited from bottom to top with alpha blending.
     * Results are cached and only recomputed when layers change.
     *
     * @return composited canvas (cached)
     */
    public PixelCanvas compositeLayersToCanvas() {
        // Return cached result if still valid
        if (!compositeCacheDirty && compositedCache != null) {
            return compositedCache;
        }

        // Recomposite layers
        PixelCanvas result = new PixelCanvas(canvasWidth, canvasHeight);

        // Composite layers from bottom to top
        for (Layer layer : layers) {
            if (!layer.isVisible()) {
                continue; // Skip invisible layers
            }

            PixelCanvas layerCanvas = layer.getCanvas();
            float opacity = layer.getOpacity();

            // Blend each pixel
            for (int y = 0; y < canvasHeight; y++) {
                for (int x = 0; x < canvasWidth; x++) {
                    int srcPixel = layerCanvas.getPixel(x, y);

                    // Apply layer opacity to pixel alpha
                    int[] rgba = PixelCanvas.unpackRGBA(srcPixel);
                    rgba[3] = (int) (rgba[3] * opacity);
                    int adjustedPixel = PixelCanvas.packRGBA(rgba[0], rgba[1], rgba[2], rgba[3]);

                    // Blend with destination
                    int dstPixel = result.getPixel(x, y);
                    int blended = PixelCanvas.blendColors(adjustedPixel, dstPixel);
                    result.setPixel(x, y, blended);
                }
            }
        }

        // Cache the result
        compositedCache = result;
        compositeCacheDirty = false;

        return result;
    }

    /**
     * Composite all visible layers EXCEPT a specified layer.
     * Useful for creating background context during layer transformations.
     * This is NOT cached as it's used for temporary preview purposes.
     *
     * @param excludeLayer Layer to exclude from compositing (typically the active layer)
     * @return composited canvas with excluded layer omitted
     */
    public PixelCanvas compositeLayersExcluding(Layer excludeLayer) {
        PixelCanvas result = new PixelCanvas(canvasWidth, canvasHeight);

        // Composite layers from bottom to top, excluding the specified layer
        for (Layer layer : layers) {
            if (layer == excludeLayer) {
                continue; // Skip the excluded layer
            }
            if (!layer.isVisible()) {
                continue; // Skip invisible layers
            }

            PixelCanvas layerCanvas = layer.getCanvas();
            float opacity = layer.getOpacity();

            // Blend each pixel
            for (int y = 0; y < canvasHeight; y++) {
                for (int x = 0; x < canvasWidth; x++) {
                    int srcPixel = layerCanvas.getPixel(x, y);

                    // Apply layer opacity to pixel alpha
                    int[] rgba = PixelCanvas.unpackRGBA(srcPixel);
                    rgba[3] = (int) (rgba[3] * opacity);
                    int adjustedPixel = PixelCanvas.packRGBA(rgba[0], rgba[1], rgba[2], rgba[3]);

                    // Blend with destination
                    int dstPixel = result.getPixel(x, y);
                    int blended = PixelCanvas.blendColors(adjustedPixel, dstPixel);
                    result.setPixel(x, y, blended);
                }
            }
        }

        return result;
    }

    /**
     * Mark the compositing cache as dirty (needs to be regenerated).
     * Call this whenever layer pixel data changes.
     */
    public void markCompositeDirty() {
        compositeCacheDirty = true;
    }
}
