package com.openmason.ui.components.textureCreator.commands.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.Command;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.move.SelectionSnapshot;
import com.openmason.ui.components.textureCreator.tools.move.TransformedImage;
import com.openmason.ui.components.textureCreator.tools.move.TransformedSelectionRegion;
import com.openmason.ui.components.textureCreator.tools.move.TransformationState;

import java.awt.Rectangle;
import java.util.Objects;

/**
 * Applies the finalised transformation computed by the move tool. The command
 * stores original pixel data, overwritten destination pixels, and updated
 * selection information to provide reversible non-destructive editing.
 */
public final class MoveSelectionCommand implements Command {

    private final PixelCanvas canvas;
    private final SelectionManager selectionManager;
    private final SelectionRegion originalSelection;
    private final SelectionSnapshot snapshot;
    private final TransformationState transform;
    private final TransformedImage transformedImage;
    private final TransformedSelectionRegion transformedSelection;
    private final boolean skipTransparentPixels;

    private final int[] overwrittenPixels;
    private final boolean[] overwrittenMask;

    private MoveSelectionCommand(PixelCanvas canvas,
                                 SelectionManager selectionManager,
                                 SelectionRegion originalSelection,
                                 SelectionSnapshot snapshot,
                                 TransformationState transform,
                                 TransformedImage transformedImage,
                                 TransformedSelectionRegion transformedSelection,
                                 boolean skipTransparentPixels,
                                 int[] overwrittenPixels,
                                 boolean[] overwrittenMask) {
        this.canvas = canvas;
        this.selectionManager = selectionManager;
        this.originalSelection = originalSelection;
        this.snapshot = snapshot;
        this.transform = transform;
        this.transformedImage = transformedImage;
        this.transformedSelection = transformedSelection;
        this.skipTransparentPixels = skipTransparentPixels;
        this.overwrittenPixels = overwrittenPixels;
        this.overwrittenMask = overwrittenMask;
    }

    public static MoveSelectionCommand create(PixelCanvas canvas,
                                              SelectionManager selectionManager,
                                              SelectionRegion originalSelection,
                                              SelectionSnapshot snapshot,
                                              TransformationState transform,
                                              TransformedImage transformedImage,
                                              TransformedSelectionRegion transformedSelection,
                                              boolean skipTransparentPixels) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(transformedImage, "transformedImage");

        if (transform.isIdentity() || transformedImage.isEmpty()) {
            return null;
        }

        Rectangle bounds = transformedImage.bounds();
        int[] overwrittenPixels = new int[bounds.width * bounds.height];
        boolean[] overwrittenMask = new boolean[bounds.width * bounds.height];

        boolean[] mask = transformedImage.mask();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int index = dy * bounds.width + dx;
                if (!mask[index]) {
                    continue;
                }
                overwrittenMask[index] = true;
                overwrittenPixels[index] = canvas.getPixel(bounds.x + dx, canvasY);
            }
        }

        return new MoveSelectionCommand(
                canvas,
                selectionManager,
                originalSelection,
                snapshot,
                transform,
                transformedImage,
                transformedSelection,
                skipTransparentPixels,
                overwrittenPixels,
                overwrittenMask
        );
    }

    @Override
    public void execute() {
        canvas.setBypassSelectionConstraint(true);
        try {
            clearSourceArea();
            applyTransformedPixels();
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }

        if (selectionManager != null && transformedSelection != null) {
            selectionManager.setActiveSelection(transformedSelection);
        }
    }

    @Override
    public void undo() {
        canvas.setBypassSelectionConstraint(true);
        try {
            restoreOverwrittenPixels();
            restoreSourcePixels();
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }

        if (selectionManager != null && originalSelection != null) {
            selectionManager.setActiveSelection(originalSelection);
        }
    }

    @Override
    public String getDescription() {
        StringBuilder builder = new StringBuilder("Transform selection");
        if (!isZero(transform.translateX()) || !isZero(transform.translateY())) {
            builder.append(String.format(" — move (%.1f, %.1f)", transform.translateX(), transform.translateY()));
        }
        if (!isOne(transform.scaleX()) || !isOne(transform.scaleY())) {
            builder.append(String.format(" — scale (%.3f, %.3f)", transform.scaleX(), transform.scaleY()));
        }
        if (!isZero(transform.rotationDegrees())) {
            builder.append(String.format(" — rotate %.1f°", transform.rotationDegrees()));
        }
        return builder.toString();
    }

    public boolean hasChanges() {
        return transformedSelection != null && !transformedSelection.isEmpty();
    }

    public SelectionRegion getTransformedSelection() {
        return transformedSelection;
    }

    public TransformationState getTransform() {
        return transform;
    }

    private void clearSourceArea() {
        Rectangle bounds = snapshot.bounds();
        boolean[] mask = snapshot.mask();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int index = dy * bounds.width + dx;
                if (!mask[index]) {
                    continue;
                }
                canvas.setPixel(bounds.x + dx, canvasY, 0x00000000);
            }
        }
    }

    private void applyTransformedPixels() {
        Rectangle bounds = transformedImage.bounds();
        boolean[] mask = transformedImage.mask();
        int[] pixels = transformedImage.pixels();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int index = dy * bounds.width + dx;
                if (!mask[index]) {
                    continue;
                }
                int pixel = pixels[index];
                // Check transparency based on preference
                // If skipTransparentPixels is true, only apply non-transparent pixels
                // If skipTransparentPixels is false, apply all pixels including transparent ones
                if (!skipTransparentPixels || (pixel >>> 24) > 0) { // Check alpha channel
                    canvas.setPixel(bounds.x + dx, canvasY, pixel);
                }
            }
        }
    }

    private void restoreOverwrittenPixels() {
        Rectangle bounds = transformedImage.bounds();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int index = dy * bounds.width + dx;
                if (!overwrittenMask[index]) {
                    continue;
                }
                canvas.setPixel(bounds.x + dx, canvasY, overwrittenPixels[index]);
            }
        }
    }

    private void restoreSourcePixels() {
        Rectangle bounds = snapshot.bounds();
        boolean[] mask = snapshot.mask();
        int[] pixels = snapshot.pixels();

        for (int dy = 0; dy < bounds.height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < bounds.width; dx++) {
                int index = dy * bounds.width + dx;
                if (!mask[index]) {
                    continue;
                }
                canvas.setPixel(bounds.x + dx, canvasY, pixels[index]);
            }
        }
    }

    private static boolean isZero(double value) {
        return Math.abs(value) < 1.0e-6;
    }

    private static boolean isOne(double value) {
        return Math.abs(value - 1.0) < 1.0e-6;
    }
}
