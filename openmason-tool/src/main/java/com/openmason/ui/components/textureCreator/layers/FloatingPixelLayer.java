package com.openmason.ui.components.textureCreator.layers;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for non-destructive floating pixel preview layers.
 *
 * <p>Floating pixel layers show transformed or positioned content without modifying
 * the source canvas. They composite floating pixels over background layers to create
 * temporary preview canvases for visualization.</p>
 *
 * <p>Subclasses implement the pixel positioning/transformation logic by providing
 * a method that returns the floating pixel color at any given canvas coordinate.</p>
 *
 * <h3>Design Philosophy</h3>
 * <ul>
 *   <li><b>Non-destructive:</b> Source pixels are never modified, only composited</li>
 *   <li><b>Reusable:</b> Common preview logic shared across paste, move, and transform tools</li>
 *   <li><b>Flexible:</b> Supports both simple positioning and complex transformations</li>
 *   <li><b>Layer-aware:</b> Handles single-layer and multi-layer contexts</li>
 * </ul>
 *
 * @author Open Mason Team
 */
public abstract class FloatingPixelLayer {

    private static final Logger logger = LoggerFactory.getLogger(FloatingPixelLayer.class);

    /**
     * Get the floating pixel color at the specified canvas coordinate.
     *
     * <p>This method is called during preview generation for each canvas pixel.
     * Implementations should return the transformed/positioned pixel color if
     * the coordinate contains a floating pixel, or {@code null} if no floating
     * pixel exists at this position.</p>
     *
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @return floating pixel color (ARGB format), or {@code null} if no pixel at this position
     */
    protected abstract Integer getFloatingPixelAt(int canvasX, int canvasY);

    /**
     * Create a preview canvas showing floating content composited over the source canvas.
     *
     * <p>This creates a temporary visualization without modifying the source canvas.
     * The source canvas content is preserved, and floating pixels are composited on top
     * using alpha blending for semi-transparent pixels.</p>
     *
     * @param sourceCanvas source canvas (layer receiving the floating content)
     * @return preview canvas with floating content composited
     */
    public PixelCanvas createPreviewCanvas(PixelCanvas sourceCanvas) {
        // Create preview canvas with same dimensions as source
        PixelCanvas preview = new PixelCanvas(sourceCanvas.getWidth(), sourceCanvas.getHeight());

        // Copy source canvas content (non-destructive)
        System.arraycopy(
            sourceCanvas.getPixels(),
            0,
            preview.getPixels(),
            0,
            sourceCanvas.getPixels().length
        );

        // Composite floating pixels over the source
        compositeFloatingPixels(preview);

        return preview;
    }

    /**
     * Create a multi-layer preview showing floating content composited with background layers.
     *
     * <p>Used when applying floating content to a specific layer in a multi-layer context.
     * The preview shows the full layer stack: background layers + active layer + floating content.</p>
     *
     * @param backgroundCanvas composited canvas of all layers except active
     * @param activeLayerCanvas active layer receiving the floating content
     * @return preview canvas with full layer stack + floating content
     */
    public PixelCanvas createMultiLayerPreviewCanvas(PixelCanvas backgroundCanvas, PixelCanvas activeLayerCanvas) {
        // Start with background layers
        PixelCanvas preview = new PixelCanvas(backgroundCanvas.getWidth(), backgroundCanvas.getHeight());
        System.arraycopy(
            backgroundCanvas.getPixels(),
            0,
            preview.getPixels(),
            0,
            backgroundCanvas.getPixels().length
        );

        // Composite active layer content (where floating content will appear)
        compositeActiveLayer(preview, activeLayerCanvas);

        // Composite floating pixels on top
        compositeFloatingPixels(preview);

        return preview;
    }

    /**
     * Composite the active layer onto the preview canvas.
     *
     * <p>This method can be overridden by subclasses that need special handling
     * for the active layer (e.g., masking out selected regions). Default implementation
     * simply composites the entire active layer.</p>
     *
     * @param preview preview canvas being built
     * @param activeLayer active layer to composite
     */
    protected void compositeActiveLayer(PixelCanvas preview, PixelCanvas activeLayer) {
        int width = activeLayer.getWidth();
        int height = activeLayer.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int activeColor = activeLayer.getPixel(x, y);

                // Skip fully transparent pixels
                if ((activeColor >>> 24) == 0) {
                    continue;
                }

                int bgColor = preview.getPixel(x, y);
                int blended = PixelCanvas.blendColors(activeColor, bgColor);
                preview.setPixel(x, y, blended);
            }
        }
    }

    /**
     * Composite floating pixels onto the preview canvas.
     *
     * <p>Iterates through all canvas coordinates and composites floating pixels
     * returned by {@link #getFloatingPixelAt(int, int)}. Only non-transparent
     * floating pixels are composited using alpha blending.</p>
     *
     * <p><b>Note for subclasses:</b> This method can be overridden to customize
     * how transparent pixels are handled. The default implementation skips fully
     * transparent pixels (alpha=0), but subclasses may want to explicitly set them
     * to create visible "holes" in the preview.</p>
     *
     * @param preview preview canvas to composite onto
     */
    protected void compositeFloatingPixels(PixelCanvas preview) {
        int width = preview.getWidth();
        int height = preview.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Integer floatingPixel = getFloatingPixelAt(x, y);

                if (floatingPixel == null) {
                    continue;  // No floating pixel at this position
                }

                // Extract alpha channel
                int alpha = (floatingPixel >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;  // Fully transparent
                }

                // Blend floating pixel with existing preview content
                int existingColor = preview.getPixel(x, y);
                int blended = PixelCanvas.blendColors(floatingPixel, existingColor);
                preview.setPixel(x, y, blended);
            }
        }
    }

    /**
     * Helper method to check if a pixel is fully transparent.
     *
     * @param color pixel color in ARGB format
     * @return true if alpha channel is 0 (fully transparent)
     */
    protected static boolean isTransparent(int color) {
        return (color >>> 24) == 0;
    }

    /**
     * Helper method to check if canvas coordinates are valid.
     *
     * @param canvas canvas to check against
     * @param x X coordinate
     * @param y Y coordinate
     * @return true if coordinates are within canvas bounds
     */
    protected static boolean isValidCoordinate(PixelCanvas canvas, int x, int y) {
        return canvas.isValidCoordinate(x, y);
    }
}
