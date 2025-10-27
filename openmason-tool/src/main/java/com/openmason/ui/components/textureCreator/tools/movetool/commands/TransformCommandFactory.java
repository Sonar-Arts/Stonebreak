package com.openmason.ui.components.textureCreator.tools.movetool.commands;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.Command;
import com.openmason.ui.components.textureCreator.commands.RotateSelectionCommand;
import com.openmason.ui.components.textureCreator.commands.ScaleSelectionCommand;
import com.openmason.ui.components.textureCreator.commands.TranslateSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.FreeSelection;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.movetool.state.TransformState;

import java.util.HashSet;
import java.util.Set;

/**
 * Factory for creating transform commands based on completed drag operations.
 * Follows Factory Pattern and Single Responsibility Principle.
 * Returns both the command and the updated selection region.
 *
 * @author Open Mason Team
 */
public class TransformCommandFactory {

    /**
     * Result of command creation containing the command and updated selection.
     */
    public static class CommandResult {
        private final Command command;
        private final SelectionRegion updatedSelection;

        public CommandResult(Command command, SelectionRegion updatedSelection) {
            this.command = command;
            this.updatedSelection = updatedSelection;
        }

        public Command getCommand() {
            return command;
        }

        public SelectionRegion getUpdatedSelection() {
            return updatedSelection;
        }

        public boolean hasCommand() {
            return command != null;
        }
    }

    /**
     * Creates a move command from completed translation.
     *
     * @param canvas Current pixel canvas
     * @param currentSelection Current selection region
     * @param state Transform state with original and preview coordinates
     * @return CommandResult with move command and updated selection, or null command if no movement
     */
    public CommandResult createMoveCommand(PixelCanvas canvas, SelectionRegion currentSelection,
                                           TransformState state) {
        int deltaX = state.getPreviewX1() - state.getOriginalX1();
        int deltaY = state.getPreviewY1() - state.getOriginalY1();

        if (deltaX != 0 || deltaY != 0) {
            Command command = new TranslateSelectionCommand(canvas, currentSelection, deltaX, deltaY);
            SelectionRegion updatedSelection = currentSelection.translate(deltaX, deltaY);
            return new CommandResult(command, updatedSelection);
        }

        return new CommandResult(null, currentSelection);
    }

    /**
     * Creates a scale command from completed scaling/stretching operation.
     *
     * @param canvas Current pixel canvas
     * @param currentSelection Current selection region
     * @param state Transform state with scale factors and preview coordinates
     * @return CommandResult with scale command and updated selection, or null command if no scaling
     */
    public CommandResult createScaleCommand(PixelCanvas canvas, SelectionRegion currentSelection,
                                            TransformState state) {
        // Check if we're scaling a free selection - maintain its shape
        if (currentSelection instanceof FreeSelection) {
            return createFreeSelectionScaleCommand(canvas, (FreeSelection) currentSelection, state);
        } else {
            return createRectangularScaleCommand(canvas, currentSelection, state);
        }
    }

    /**
     * Creates a rotate command from completed rotation operation.
     *
     * @param canvas Current pixel canvas
     * @param currentSelection Current selection region
     * @param state Transform state with rotation angle
     * @return CommandResult with rotate command and updated selection, or null command if no rotation
     */
    public CommandResult createRotateCommand(PixelCanvas canvas, SelectionRegion currentSelection,
                                             TransformState state) {
        if (Math.abs(state.getRotationAngleDegrees()) > 0.1) {
            Command command = new RotateSelectionCommand(canvas, currentSelection, state.getRotationAngleDegrees());

            // For free selections, update to rotated coordinates
            SelectionRegion updatedSelection;
            if (currentSelection instanceof FreeSelection) {
                FreeSelection freeSelection = (FreeSelection) currentSelection;
                updatedSelection = freeSelection.rotate(state.getRotationAngleDegrees());
            } else {
                // Rectangular selection bounds don't change for rotation
                updatedSelection = currentSelection;
            }

            return new CommandResult(command, updatedSelection);
        }

        return new CommandResult(null, currentSelection);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private CommandResult createFreeSelectionScaleCommand(PixelCanvas canvas, FreeSelection freeSelection,
                                                          TransformState state) {
        // Scale the free selection to maintain its shape
        FreeSelection scaledFreeSelection = freeSelection.scale(
            state.getScaleAnchorX(), state.getScaleAnchorY(),
            state.getScaleFactorX(), state.getScaleFactorY());

        // For non-uniform edge stretching, trim the scaled selection to exact target bounds
        // This removes fill artifacts at anchor edges while maintaining free-form shape
        boolean isNonUniformStretch =
            (Math.abs(state.getScaleFactorX() - 1.0) < 0.001 && Math.abs(state.getScaleFactorY() - 1.0) >= 0.001) ||
            (Math.abs(state.getScaleFactorY() - 1.0) < 0.001 && Math.abs(state.getScaleFactorX() - 1.0) >= 0.001);

        if (isNonUniformStretch) {
            // Trim pixels outside target bounds to prevent anchor edge artifacts
            scaledFreeSelection = trimFreeSelectionToBounds(scaledFreeSelection,
                state.getPreviewX1(), state.getPreviewY1(),
                state.getPreviewX2(), state.getPreviewY2());
        }

        if (!scaledFreeSelection.equals(freeSelection)) {
            // Use rectangular bounds for the command, but maintain the free selection
            RectangularSelection targetBounds = new RectangularSelection(
                state.getPreviewX1(), state.getPreviewY1(),
                state.getPreviewX2(), state.getPreviewY2());
            Command command = new ScaleSelectionCommand(canvas, freeSelection, targetBounds);
            return new CommandResult(command, scaledFreeSelection);
        }

        return new CommandResult(null, freeSelection);
    }

    private CommandResult createRectangularScaleCommand(PixelCanvas canvas, SelectionRegion currentSelection,
                                                        TransformState state) {
        RectangularSelection scaledSelection = new RectangularSelection(
            state.getPreviewX1(), state.getPreviewY1(),
            state.getPreviewX2(), state.getPreviewY2());

        if (!scaledSelection.equals(currentSelection)) {
            Command command = new ScaleSelectionCommand(canvas, currentSelection, scaledSelection);
            return new CommandResult(command, scaledSelection);
        }

        return new CommandResult(null, currentSelection);
    }

    /**
     * Trims a free selection to only include pixels within the specified bounds.
     * Used to remove fill artifacts at anchor edges during non-uniform scaling.
     *
     * @param selection The free selection to trim
     * @param x1 Left bound (inclusive)
     * @param y1 Top bound (inclusive)
     * @param x2 Right bound (inclusive)
     * @param y2 Bottom bound (inclusive)
     * @return A new FreeSelection containing only pixels within bounds
     */
    private FreeSelection trimFreeSelectionToBounds(FreeSelection selection, int x1, int y1, int x2, int y2) {
        Set<FreeSelection.Pixel> trimmedPixels = new HashSet<>();

        for (FreeSelection.Pixel pixel : selection.getPixels()) {
            if (pixel.x >= x1 && pixel.x <= x2 && pixel.y >= y1 && pixel.y <= y2) {
                trimmedPixels.add(pixel);
            }
        }

        // If no pixels remain after trimming, return original selection
        return trimmedPixels.isEmpty() ? selection : new FreeSelection(trimmedPixels);
    }
}
