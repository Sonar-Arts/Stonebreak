package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.CubeNetValidator;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.layers.FloatingPixelLayer;

import java.awt.Rectangle;

/**
 * Non-destructive floating preview layer for move/transform operations.
 *
 * <p>Shows transformed selection content without modifying the source canvas.
 * Extends {@link FloatingPixelLayer} to provide non-destructive preview generation.</p>
 *
 * <p><b>Preview Behavior:</b> During preview, this layer conditionally creates a hole based on
 * the operation type:
 * <ul>
 *   <li><b>Regular Move:</b> Always creates a hole at the original selection location (pixels were moved)</li>
 *   <li><b>Paste:</b> Never creates a hole (pixels were added, not moved)</li>
 * </ul>
 * The source canvas is never actually modified - the hole only appears in the preview. The actual
 * modification happens when the move command is committed via
 * {@link com.openmason.ui.components.textureCreator.commands.move.MoveSelectionCommand}.</p>
 *
 * <h3>Design Philosophy</h3>
 * <ul>
 *   <li><b>Non-destructive preview:</b> Source canvas is never modified during preview</li>
 *   <li><b>Operation-aware holes:</b> Move creates hole, paste does not</li>
 *   <li><b>Paste-friendly:</b> Pasted content can be transformed without showing a hole</li>
 *   <li><b>Transform overlay:</b> Transformed pixels are shown as a floating layer</li>
 *   <li><b>Visual accuracy:</b> Preview accurately represents the final result after commit</li>
 * </ul>
 *
 * @author Open Mason Team
 */
public final class TransformPreviewLayer extends FloatingPixelLayer {

    private final SelectionSnapshot snapshot;
    private final TransformedImage transformedImage;
    private final boolean isPasteOperation;

    /**
     * Create transform preview layer.
     *
     * @param snapshot selection snapshot containing original pixel data
     * @param transformedImage transformed result to preview
     * @param isPasteOperation true if this is a paste operation (no hole should be created)
     */
    TransformPreviewLayer(SelectionSnapshot snapshot, TransformedImage transformedImage, boolean isPasteOperation) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot cannot be null");
        }
        if (transformedImage == null) {
            throw new IllegalArgumentException("Transformed image cannot be null");
        }

        this.snapshot = snapshot;
        this.transformedImage = transformedImage;
        this.isPasteOperation = isPasteOperation;
    }

    /**
     * Get the floating pixel color at the specified canvas coordinate.
     *
     * <p>Returns transformed pixels at their new positions. The hole at the original
     * selection location is created separately by {@link #compositeActiveLayer}.</p>
     *
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @return transformed pixel color, or null if position is outside transformed area
     */
    @Override
    protected Integer getFloatingPixelAt(int canvasX, int canvasY) {
        // Skip cube-net transparency zones - transformed pixels should not appear there
        if (!CubeNetValidator.isEditablePixel(canvasX, canvasY,
                snapshot.canvasWidth(), snapshot.canvasHeight())) {
            return null;  // Don't show transformed pixels in transparency zones
        }

        // Check if this position has a transformed pixel
        Rectangle transformedBounds = transformedImage.bounds();
        if (!transformedBounds.contains(canvasX, canvasY)) {
            return null;  // Not in transformed area
        }

        // Calculate local coordinate within transformed image
        int localX = canvasX - transformedBounds.x;
        int localY = canvasY - transformedBounds.y;
        int index = localY * transformedBounds.width + localX;

        // Check if this position has a transformed pixel
        boolean[] transformedMask = transformedImage.mask();
        if (!transformedMask[index]) {
            return null;  // No pixel at this position (outside selection shape)
        }

        // Return transformed pixel color
        int[] pixels = transformedImage.pixels();
        int color = pixels[index];

        // Return null for fully transparent pixels (optimization)
        if (isTransparent(color)) {
            return null;
        }

        return color;
    }

    /**
     * Composite active layer with optional hole at the original selection location.
     *
     * <p>During preview, a hole is only created for regular move operations (not paste).
     * Paste operations never create a hole because the content was pasted (added) rather
     * than moved from an existing location.</p>
     *
     * @param preview preview canvas being built
     * @param activeLayer active layer to composite
     */
    @Override
    protected void compositeActiveLayer(PixelCanvas preview, PixelCanvas activeLayer) {
        int width = activeLayer.getWidth();
        int height = activeLayer.getHeight();

        // Get original bounds for hole detection
        Rectangle originalBounds = snapshot.bounds();
        boolean[] originalMask = snapshot.mask();

        // NEVER create a hole for paste operations
        // Only create a hole for regular move operations
        boolean shouldCreateHole = !isPasteOperation;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Skip cube-net transparency zones (they should never show holes OR content)
                if (!CubeNetValidator.isEditablePixel(x, y, width, height)) {
                    continue;  // Don't composite anything in transparency zones
                }

                // Check if this pixel is within the original selection bounds
                boolean inOriginalSelection = false;
                if (x >= originalBounds.x && x < originalBounds.x + originalBounds.width &&
                    y >= originalBounds.y && y < originalBounds.y + originalBounds.height) {

                    // Calculate index in original mask
                    int localX = x - originalBounds.x;
                    int localY = y - originalBounds.y;
                    int index = localY * originalBounds.width + localX;

                    // Check if this pixel was part of the original selection
                    inOriginalSelection = originalMask[index];
                }

                // Skip pixels (create hole) only for regular move operations, never for paste
                if (inOriginalSelection && shouldCreateHole) {
                    continue;
                }

                // Composite all other pixels normally
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
     * Get the selection snapshot.
     * @return selection snapshot
     */
    public SelectionSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Get the transformed image.
     * @return transformed image
     */
    public TransformedImage getTransformedImage() {
        return transformedImage;
    }
}
