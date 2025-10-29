package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;

import java.awt.Rectangle;
/**
 * Lightweight overlay generator used by {@link MoveToolController}. The layer
 * never mutates the live canvas; instead it composes a temporary canvas that
 * merges the untouched pixels with the transformed snapshot for preview
 * rendering.
 */
public final class TransformPreviewLayer {

    private final SelectionSnapshot snapshot;
    private final TransformedImage transformedImage;

    TransformPreviewLayer(SelectionSnapshot snapshot, TransformedImage transformedImage) {
        this.snapshot = snapshot;
        this.transformedImage = transformedImage;
    }

    public PixelCanvas createPreviewCanvas(PixelCanvas activeLayer) {
        PixelCanvas preview = cloneCanvas(activeLayer);
        applySelectionMask(preview, snapshot);
        applyTransformedPixels(preview, transformedImage);
        return preview;
    }

    public PixelCanvas createMultiLayerPreviewCanvas(PixelCanvas background, PixelCanvas activeLayer) {
        PixelCanvas preview = cloneCanvas(background);
        compositeLayerOutsideSelection(preview, activeLayer, snapshot);
        applyTransformedPixels(preview, transformedImage);
        return preview;
    }

    private static PixelCanvas cloneCanvas(PixelCanvas source) {
        PixelCanvas copy = new PixelCanvas(source.getWidth(), source.getHeight());
        System.arraycopy(source.getPixels(), 0, copy.getPixels(), 0, source.getPixels().length);
        return copy;
    }

    private static void applySelectionMask(PixelCanvas canvas, SelectionSnapshot snapshot) {
        Rectangle bounds = snapshot.bounds();
        boolean[] mask = snapshot.mask();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int canvasX = bounds.x + dx;
                int index = dy * bounds.width + dx;
                if (mask[index]) {
                    canvas.setPixel(canvasX, canvasY, 0x00000000);
                }
            }
        }
    }

    private static void compositeLayerOutsideSelection(PixelCanvas target,
                                                       PixelCanvas activeLayer,
                                                       SelectionSnapshot snapshot) {
        Rectangle bounds = snapshot.bounds();
        boolean[] mask = snapshot.mask();

        int width = activeLayer.getWidth();
        int height = activeLayer.getHeight();

        int[] activePixels = activeLayer.getPixels();
        int[] targetPixels = target.getPixels();

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = activePixels[rowOffset + x];
                if (pixel >>> 24 == 0) {
                    continue;
                }

                if (isWithinMask(bounds, mask, x, y)) {
                    continue;
                }

                int targetIndex = rowOffset + x;
                targetPixels[targetIndex] = PixelCanvas.blendColors(pixel, targetPixels[targetIndex]);
            }
        }
    }

    private static boolean isWithinMask(Rectangle bounds, boolean[] mask, int canvasX, int canvasY) {
        if (!bounds.contains(canvasX, canvasY)) {
            return false;
        }
        int localX = canvasX - bounds.x;
        int localY = canvasY - bounds.y;
        int index = localY * bounds.width + localX;
        return mask[index];
    }

    private static void applyTransformedPixels(PixelCanvas canvas, TransformedImage image) {
        Rectangle bounds = image.bounds();
        if (bounds.width == 0 || bounds.height == 0) {
            return;
        }

        boolean[] mask = image.mask();
        int[] colours = image.pixels();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int index = dy * bounds.width + dx;
                if (!mask[index]) {
                    continue;
                }
                int canvasX = bounds.x + dx;
                canvas.setPixel(canvasX, canvasY, colours[index]);
            }
        }
    }
}
