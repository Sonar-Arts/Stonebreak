package com.openmason.ui.components.textureCreator.tools.move.modules;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Non-destructive transform layer that overlays the base canvas.
 *
 * This layer holds extracted pixels and transform state WITHOUT modifying the canvas.
 * The original canvas remains untouched until commit() is explicitly called.
 *
 * Benefits:
 * - Zero data loss risk - canvas never modified during preview
 * - Infinite undo - just discard layer, no restoration needed
 * - Crash-safe - original pixels always intact on canvas
 * - Performance - lazy computation with caching
 * - Clean separation - preview vs commit clearly separated
 *
 * Architecture:
 * 1. Extract pixels from canvas (read-only, no clearing)
 * 2. Apply transforms to in-memory copy
 * 3. Render overlay during preview
 * 4. Commit: atomic canvas modification
 * 5. Cancel: discard layer, canvas unchanged
 *
 * @author Open Mason Team
 */
public class TransformLayer {

    // Original pixel data (read-only after construction)
    private final Map<Point, Integer> extractedPixels;
    private final Rectangle originalBounds;
    private final SelectionRegion originalSelection;

    // Transform state (mutable)
    private TransformState transform;
    private boolean visible;

    // Lazy-computed cache for performance
    private Map<Point, Integer> cachedTransformedPixels;
    private TransformState cachedTransform;

    // Pixel transformer for applying transforms
    private final PixelTransformer pixelTransformer;

    /**
     * Creates a non-destructive transform layer by reading pixels from canvas.
     *
     * IMPORTANT: This does NOT modify the canvas - pixels are read-only copied.
     *
     * @param canvas The pixel canvas to read from
     * @param selection The selection region to extract
     */
    public TransformLayer(PixelCanvas canvas, SelectionRegion selection) {
        if (canvas == null || selection == null || selection.isEmpty()) {
            throw new IllegalArgumentException("Canvas and selection must be valid");
        }

        this.pixelTransformer = new PixelTransformer();
        this.extractedPixels = readPixelsNonDestructive(canvas, selection);
        this.originalBounds = new Rectangle(selection.getBounds());
        this.originalSelection = selection;
        this.transform = TransformState.identity();
        this.visible = true;

        // Initialize cache as null (lazy computation)
        this.cachedTransformedPixels = null;
        this.cachedTransform = null;
    }

    /**
     * Reads pixels from canvas WITHOUT clearing them (non-destructive).
     * This is a pure read operation - canvas remains unchanged.
     */
    private Map<Point, Integer> readPixelsNonDestructive(PixelCanvas canvas, SelectionRegion selection) {
        Map<Point, Integer> pixels = new HashMap<>();
        Rectangle bounds = selection.getBounds();

        // Extract pixels within selection bounds
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (selection.contains(x, y) && canvas.isValidCoordinate(x, y)) {
                    // Store as relative coordinates
                    Point relativePoint = new Point(x - bounds.x, y - bounds.y);
                    int color = canvas.getPixel(x, y);
                    pixels.put(relativePoint, color);
                }
            }
        }

        return pixels;
    }

    /**
     * Updates the transform state (cheap operation - no computation).
     * Transformed pixels will be recomputed lazily on next access.
     *
     * @param newTransform The new transformation state
     */
    public void setTransform(TransformState newTransform) {
        if (newTransform == null) {
            throw new IllegalArgumentException("Transform cannot be null");
        }

        this.transform = newTransform;
        invalidateCache();
    }

    /**
     * Gets the current transform state.
     *
     * @return Current transformation state
     */
    public TransformState getTransform() {
        return transform;
    }

    /**
     * Gets the original selection before any transformation.
     *
     * @return Original selection region
     */
    public SelectionRegion getOriginalSelection() {
        return originalSelection;
    }

    /**
     * Gets the original bounds before any transformation.
     *
     * @return Original bounding rectangle
     */
    public Rectangle getOriginalBounds() {
        return new Rectangle(originalBounds);
    }

    /**
     * Gets the original extracted pixels (relative coordinates).
     *
     * @return Map of original pixels (read-only)
     */
    public Map<Point, Integer> getOriginalPixels() {
        return new HashMap<>(extractedPixels);
    }

    /**
     * Gets transformed pixels for rendering (lazy computation with caching).
     * Returns absolute canvas coordinates for direct rendering.
     *
     * This method is optimized - transformation is only computed when:
     * 1. First access
     * 2. Transform state has changed since last computation
     *
     * @return Map of transformed pixels in absolute canvas coordinates
     */
    public Map<Point, Integer> getTransformedPixels() {
        // Check if cache is valid
        if (cachedTransformedPixels == null || !transform.equals(cachedTransform)) {
            // Recompute transformation
            cachedTransformedPixels = pixelTransformer.applyTransform(
                    extractedPixels, transform, originalBounds);
            cachedTransform = transform;
        }

        return cachedTransformedPixels;
    }

    /**
     * Calculates the transformed selection region.
     *
     * @return Transformed selection region
     */
    public SelectionRegion getTransformedSelection() {
        return pixelTransformer.transformSelection(originalSelection, transform);
    }

    /**
     * Checks if this layer has any actual changes.
     *
     * @return true if transform is not identity, false otherwise
     */
    public boolean hasChanges() {
        return !transform.isIdentity();
    }

    /**
     * Sets layer visibility.
     *
     * @param visible true to show layer, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Checks if layer is visible.
     *
     * @return true if visible, false otherwise
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Commits the transformation to canvas (destructive operation).
     *
     * This is the ONLY method that modifies the canvas:
     * 1. Clears original selection area
     * 2. Pastes transformed pixels
     *
     * After commit, this layer should be discarded.
     *
     * @param canvas The canvas to commit changes to
     */
    public void commitToCanvas(PixelCanvas canvas) {
        if (canvas == null) {
            throw new IllegalArgumentException("Canvas cannot be null");
        }

        // Get transformed pixels (uses cache if available)
        Map<Point, Integer> transformedPixels = getTransformedPixels();

        // Now modify canvas - bypass selection constraint for this operation
        canvas.setBypassSelectionConstraint(true);
        try {
            // 1. Clear original area
            clearArea(canvas, originalBounds, originalSelection);

            // 2. Paste transformed pixels
            pastePixels(canvas, transformedPixels);
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }
    }

    /**
     * Clears the original selection area on canvas.
     */
    private void clearArea(PixelCanvas canvas, Rectangle bounds, SelectionRegion selection) {
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (selection.contains(x, y) && canvas.isValidCoordinate(x, y)) {
                    canvas.setPixel(x, y, 0x00000000); // Transparent
                }
            }
        }
    }

    /**
     * Pastes pixels onto canvas at absolute coordinates.
     */
    private void pastePixels(PixelCanvas canvas, Map<Point, Integer> pixels) {
        for (Map.Entry<Point, Integer> entry : pixels.entrySet()) {
            Point point = entry.getKey();
            int color = entry.getValue();

            if (canvas.isValidCoordinate(point.x, point.y)) {
                canvas.setPixel(point.x, point.y, color);
            }
        }
    }

    /**
     * Discards this layer without modifying the canvas.
     *
     * This is the non-destructive cancel operation:
     * - Canvas never modified during preview
     * - Simply discard layer = perfect rollback
     * - No restoration needed
     */
    public void discard() {
        // Canvas was never modified, so nothing to restore
        // Just clear references for garbage collection
        cachedTransformedPixels = null;
        cachedTransform = null;
    }

    /**
     * Invalidates the transformation cache.
     * Called when transform state changes.
     */
    private void invalidateCache() {
        cachedTransformedPixels = null;
        cachedTransform = null;
    }

    /**
     * Creates a preview canvas by compositing this layer onto the base canvas.
     * This is used for real-time preview during transformation without modifying the base canvas.
     *
     * IMPORTANT: This creates a temporary copy for display only - the base canvas is NOT modified.
     *
     * @param baseCanvas The original canvas (not modified) - should be the active layer canvas
     * @return A new canvas with the layer composited on top
     */
    public PixelCanvas createPreviewCanvas(PixelCanvas baseCanvas) {
        if (baseCanvas == null) {
            throw new IllegalArgumentException("Base canvas cannot be null");
        }

        // Create a copy of the base canvas for preview
        PixelCanvas previewCanvas = new PixelCanvas(baseCanvas.getWidth(), baseCanvas.getHeight());

        // Copy all pixels from base canvas
        System.arraycopy(baseCanvas.getPixels(), 0, previewCanvas.getPixels(), 0,
                baseCanvas.getWidth() * baseCanvas.getHeight());

        // Composite the transform layer on top
        compositeOntoCanvas(previewCanvas);

        return previewCanvas;
    }

    /**
     * Creates a preview canvas for multi-layer support.
     * Composites this transform layer onto a background canvas, then onto the active layer.
     *
     * @param backgroundCanvas Canvas with all other layers composited (excluding active layer)
     * @param activeLayerCanvas The active layer canvas that this transform was created from
     * @return A new canvas with background + transformed active layer
     */
    public PixelCanvas createMultiLayerPreviewCanvas(PixelCanvas backgroundCanvas, PixelCanvas activeLayerCanvas) {
        if (backgroundCanvas == null || activeLayerCanvas == null) {
            throw new IllegalArgumentException("Canvas parameters cannot be null");
        }

        // Start with background (all layers except active)
        PixelCanvas previewCanvas = new PixelCanvas(backgroundCanvas.getWidth(), backgroundCanvas.getHeight());
        System.arraycopy(backgroundCanvas.getPixels(), 0, previewCanvas.getPixels(), 0,
                backgroundCanvas.getWidth() * backgroundCanvas.getHeight());

        // Create transformed active layer
        PixelCanvas transformedActiveLayer = createPreviewCanvas(activeLayerCanvas);

        // Composite transformed active layer on top of background with alpha blending
        int[] previewPixels = previewCanvas.getPixels();
        int[] transformedPixels = transformedActiveLayer.getPixels();

        for (int i = 0; i < previewPixels.length; i++) {
            int bgPixel = previewPixels[i];
            int fgPixel = transformedPixels[i];

            // Alpha blend foreground onto background
            int alpha = (fgPixel >> 24) & 0xFF;
            if (alpha == 0) {
                // Fully transparent - keep background
                continue;
            } else if (alpha == 255) {
                // Fully opaque - replace with foreground
                previewPixels[i] = fgPixel;
            } else {
                // Semi-transparent - blend
                int bgAlpha = (bgPixel >> 24) & 0xFF;
                int bgBlue = (bgPixel >> 16) & 0xFF;
                int bgGreen = (bgPixel >> 8) & 0xFF;
                int bgRed = bgPixel & 0xFF;

                int fgBlue = (fgPixel >> 16) & 0xFF;
                int fgGreen = (fgPixel >> 8) & 0xFF;
                int fgRed = fgPixel & 0xFF;

                float alphaRatio = alpha / 255.0f;
                float invAlpha = 1.0f - alphaRatio;

                int outAlpha = (int) (alpha + bgAlpha * invAlpha);
                int outBlue = (int) (fgBlue * alphaRatio + bgBlue * invAlpha);
                int outGreen = (int) (fgGreen * alphaRatio + bgGreen * invAlpha);
                int outRed = (int) (fgRed * alphaRatio + bgRed * invAlpha);

                previewPixels[i] = (outAlpha << 24) | (outBlue << 16) | (outGreen << 8) | outRed;
            }
        }

        return previewCanvas;
    }

    /**
     * Composites this layer onto a canvas (for preview or commit).
     * Clears the original selection area and pastes transformed pixels.
     *
     * @param canvas The canvas to composite onto
     */
    private void compositeOntoCanvas(PixelCanvas canvas) {
        canvas.setBypassSelectionConstraint(true);
        try {
            // 1. Clear original area
            clearArea(canvas, originalBounds, originalSelection);

            // 2. Paste transformed pixels
            Map<Point, Integer> transformedPixels = getTransformedPixels();
            pastePixels(canvas, transformedPixels);
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }
    }

    @Override
    public String toString() {
        return String.format("TransformLayer[bounds=%s, transform=%s, hasChanges=%s, visible=%s]",
                originalBounds, transform, hasChanges(), visible);
    }
}
