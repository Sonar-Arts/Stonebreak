package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.move.MoveSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.util.Optional;

/**
 * Encapsulates the lifetime of a non-destructive move interaction. Each
 * session owns the immutable snapshot of source pixels, the evolving
 * transformation state, and the derived preview artefacts required for
 * rendering and committing.
 */
final class MoveToolSession {

    private final SelectionSnapshot snapshot;
    private TransformationState transform;
    private TransformedImage transformedImage;
    private TransformedSelectionRegion transformedSelection;

    private MoveToolSession(SelectionSnapshot snapshot) {
        this.snapshot = snapshot;
        this.transform = TransformationState.identity();
    }

    static MoveToolSession capture(PixelCanvas canvas, SelectionRegion selection) {
        return new MoveToolSession(SelectionSnapshot.capture(canvas, selection));
    }

    SelectionSnapshot snapshot() {
        return snapshot;
    }

    TransformationState transform() {
        return transform;
    }

    void updateTransformation(TransformationState newTransform) {
        this.transform = newTransform;
        if (newTransform.isIdentity()) {
            this.transformedImage = null;
            this.transformedSelection = null;
        } else {
            this.transformedImage = TransformMath.computeTransformedImage(snapshot, newTransform);
            this.transformedSelection = TransformedSelectionRegion.fromImage(transformedImage);
        }
    }

    boolean hasPreview() {
        return transformedImage != null && !transformedImage.isEmpty();
    }

    Optional<TransformedImage> transformedImage() {
        return Optional.ofNullable(transformedImage);
    }

    Optional<TransformedSelectionRegion> transformedSelection() {
        return Optional.ofNullable(transformedSelection);
    }

    TransformPreviewLayer createPreviewLayer() {
        if (!hasPreview()) {
            return null;
        }
        return new TransformPreviewLayer(snapshot, transformedImage);
    }

    MoveSelectionCommand createCommand(PixelCanvas canvas,
                                       SelectionManager selectionManager,
                                       boolean skipTransparentPixels) {
        if (!hasPreview()) {
            return null;
        }
        return MoveSelectionCommand.create(
                canvas,
                selectionManager,
                snapshot.originalSelection(),
                snapshot,
                transform,
                transformedImage,
                transformedSelection,
                skipTransparentPixels
        );
    }
}
