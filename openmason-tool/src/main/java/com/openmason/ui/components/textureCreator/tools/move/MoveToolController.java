package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.commands.move.MoveSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import imgui.ImDrawList;

import java.awt.Rectangle;
import java.util.Objects;

/**
 * Modernised move tool implementation with non-destructive previews and precise
 * transformation maths. The controller orchestrates interaction state while
 * delegating rendering and geometry to dedicated helper classes to keep the
 * behaviour easy to maintain.
 */
public class MoveToolController implements DrawingTool {

    private final TransformOverlayRenderer overlayRenderer = new TransformOverlayRenderer();

    private SelectionManager selectionManager;
    private SelectionManager.SelectionChangeListener selectionListener;

    private MoveToolSession session;
    private TransformPreviewLayer previewLayer;
    private TransformHandle hoveredHandle = TransformHandle.NONE;
    private MoveSelectionCommand pendingCommand;

    private DragContext dragContext;
    private boolean shiftHeld = false;

    private SelectionRegion lastSelection;

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        SelectionRegion selection = getActiveSelection(canvas);
        if (selection == null || selection.isEmpty()) {
            resetInternalState();
            return;
        }

        ensureSession(canvas, selection);

        double canvasX = x + 0.5;
        double canvasY = y + 0.5;

        if (hoveredHandle != TransformHandle.NONE) {
            startHandleDrag(hoveredHandle, canvasX, canvasY);
        } else if (selection.contains(x, y)) {
            startTranslation(canvasX, canvasY);
        } else {
            resetInternalState();
        }
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (dragContext == null || session == null) {
            return;
        }

        double canvasX = x + 0.5;
        double canvasY = y + 0.5;

        TransformationState newTransform;
        if (dragContext.translation) {
            newTransform = computeTranslationTransform(canvasX, canvasY);
        } else if (dragContext.rotation) {
            newTransform = computeRotationTransform(canvasX, canvasY);
        } else {
            newTransform = computeScaleTransform(canvasX, canvasY);
        }

        session.updateTransformation(newTransform);
        previewLayer = session.createPreviewLayer();
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (dragContext == null || session == null) {
            return;
        }

        if (session.hasPreview()) {
            pendingCommand = session.createCommand(canvas, selectionManager);
        }

        session = null;
        previewLayer = null;
        dragContext = null;
        hoveredHandle = TransformHandle.NONE;
    }

    @Override
    public String getName() {
        return "Move";
    }

    @Override
    public String getDescription() {
        return "Translate, rotate, and scale selections non-destructively";
    }

    @Override
    public void reset() {
        session = null;
        previewLayer = null;
        dragContext = null;
        hoveredHandle = TransformHandle.NONE;
        // Keep pendingCommand so CanvasPanel can still apply it if required
    }

    public void cancelAndReset(PixelCanvas canvas) {
        reset();
        pendingCommand = null;
    }

    public void setModifierKeys(boolean shiftHeld) {
        this.shiftHeld = shiftHeld;
    }

    public void setSelectionManager(SelectionManager manager) {
        if (selectionListener != null && selectionManager != null) {
            selectionManager.removeSelectionChangeListener(selectionListener);
        }

        this.selectionManager = manager;

        if (manager != null) {
            selectionListener = (oldSel, newSel) -> {
                if (!Objects.equals(lastSelection, newSel)) {
                    reset();
                    lastSelection = newSel;
                }
            };
            manager.addSelectionChangeListener(selectionListener);
        } else {
            selectionListener = null;
        }
    }

    public void updateHoveredHandle(float mouseX,
                                    float mouseY,
                                    SelectionRegion selection,
                                    com.openmason.ui.components.textureCreator.canvas.CanvasState canvasState,
                                    float canvasDisplayX,
                                    float canvasDisplayY) {

        if (selection == null || selection.isEmpty()) {
            hoveredHandle = TransformHandle.NONE;
            return;
        }

        Rectangle bounds = selection.getBounds();
        TransformationState transform = session != null ? session.transform() : TransformationState.identity();

        hoveredHandle = overlayRenderer.detectHandle(mouseX, mouseY, bounds, transform, canvasState, canvasDisplayX, canvasDisplayY);
    }

    public void renderOverlay(ImDrawList drawList,
                              SelectionRegion selection,
                              com.openmason.ui.components.textureCreator.canvas.CanvasState canvasState,
                              float canvasDisplayX,
                              float canvasDisplayY) {

        if (selection == null || selection.isEmpty()) {
            return;
        }

        Rectangle bounds = selection.getBounds();
        TransformationState transform = session != null ? session.transform() : TransformationState.identity();

        overlayRenderer.render(drawList,
                bounds,
                transform,
                canvasState,
                canvasDisplayX,
                canvasDisplayY,
                hoveredHandle,
                dragContext != null ? dragContext.handle : TransformHandle.NONE);
    }

    public MoveSelectionCommand getPendingCommand() {
        return pendingCommand;
    }

    public void clearPendingCommand() {
        pendingCommand = null;
    }

    public boolean hasActiveLayer() {
        return previewLayer != null;
    }

    public TransformPreviewLayer getActiveLayer() {
        return previewLayer;
    }

    public boolean isActive() {
        return dragContext != null || (session != null && session.hasPreview());
    }

    public TransformationState getLiveTransform() {
        return session != null ? session.transform() : TransformationState.identity();
    }

    private void ensureSession(PixelCanvas canvas, SelectionRegion selection) {
        if (session == null || lastSelection == null || !lastSelection.equals(selection)) {
            session = MoveToolSession.capture(canvas, selection);
            previewLayer = null;
            lastSelection = selection;
        }
    }

    private void startHandleDrag(TransformHandle handle, double startX, double startY) {
        if (session == null) {
            return;
        }

        // Check if this is the dedicated rotation handle
        boolean isRotation = (handle == TransformHandle.ROTATE);
        dragContext = DragContext.forHandle(handle, session.snapshot(), session.transform(), startX, startY, isRotation);
    }

    private void startTranslation(double startX, double startY) {
        if (session == null) {
            return;
        }
        dragContext = DragContext.forTranslation(session.snapshot(), session.transform(), startX, startY);
    }

    private TransformationState computeTranslationTransform(double canvasX, double canvasY) {
        double deltaX = canvasX - dragContext.startCanvasX;
        double deltaY = canvasY - dragContext.startCanvasY;

        if (shiftHeld) {
            if (Math.abs(deltaX) > Math.abs(deltaY)) {
                deltaY = 0.0;
            } else {
                deltaX = 0.0;
            }
        }

        return dragContext.baseTransform.addTranslation(deltaX, deltaY);
    }

    private TransformationState computeRotationTransform(double canvasX, double canvasY) {
        double currentAngle = Math.atan2(canvasY - dragContext.pivotCanvasY, canvasX - dragContext.pivotCanvasX);
        double deltaRadians = currentAngle - dragContext.initialAngle;

        // Normalize delta to [-π, π] to avoid jumps at ±180° boundary
        deltaRadians = Math.atan2(Math.sin(deltaRadians), Math.cos(deltaRadians));
        double deltaDegrees = Math.toDegrees(deltaRadians);

        if (shiftHeld) {
            deltaDegrees = Math.round(deltaDegrees / 15.0) * 15.0;
        }

        return dragContext.baseTransform.withRotation(dragContext.baseTransform.rotationDegrees() + deltaDegrees);
    }

    private TransformationState computeScaleTransform(double canvasX, double canvasY) {
        SelectionSnapshot snapshot = dragContext.snapshot;
        TransformationState base = dragContext.baseTransform;

        double[] local = TransformMath.mapCanvasToLocal(canvasX, canvasY, snapshot, base);
        double width = Math.max(snapshot.width(), 1);
        double height = Math.max(snapshot.height(), 1);

        double targetWidthPixels = width * base.scaleX();
        double targetHeightPixels = height * base.scaleY();

        switch (dragContext.handle) {
            case SCALE_NORTH_EAST:
            case SCALE_EAST:
            case SCALE_SOUTH_EAST:
                targetWidthPixels = snapSize(local[0]);
                break;
            case SCALE_NORTH_WEST:
            case SCALE_WEST:
            case SCALE_SOUTH_WEST:
                targetWidthPixels = snapSize(width - local[0]);
                break;
            default:
                break;
        }

        switch (dragContext.handle) {
            case SCALE_NORTH_WEST:
            case SCALE_NORTH:
            case SCALE_NORTH_EAST:
                targetHeightPixels = snapSize(height - local[1]);
                break;
            case SCALE_SOUTH_WEST:
            case SCALE_SOUTH:
            case SCALE_SOUTH_EAST:
                targetHeightPixels = snapSize(local[1]);
                break;
            default:
                break;
        }

        boolean affectsX = affectsXAxis(dragContext.handle);
        boolean affectsY = affectsYAxis(dragContext.handle);

        if (shiftHeld && (affectsX || affectsY)) {
            double lockedPixels = Math.max(targetWidthPixels, targetHeightPixels);
            targetWidthPixels = lockedPixels;
            targetHeightPixels = lockedPixels;
            affectsX = true;
            affectsY = true;
        }

        double targetScaleX = affectsX ? ensureScale(targetWidthPixels / width, base.scaleX()) : base.scaleX();
        double targetScaleY = affectsY ? ensureScale(targetHeightPixels / height, base.scaleY()) : base.scaleY();

        TransformationState scaled = base.withScale(targetScaleX, targetScaleY);

        double[] newAnchorCanvas = TransformMath.mapLocalToCanvas(
                dragContext.anchorLocal[0],
                dragContext.anchorLocal[1],
                snapshot,
                scaled);

        double deltaX = dragContext.anchorCanvas[0] - newAnchorCanvas[0];
        double deltaY = dragContext.anchorCanvas[1] - newAnchorCanvas[1];

        return scaled.withTranslation(base.translateX() + deltaX, base.translateY() + deltaY);
    }

    private SelectionRegion getActiveSelection(PixelCanvas canvas) {
        if (selectionManager != null) {
            return selectionManager.getActiveSelection();
        }
        return canvas.getActiveSelection();
    }

    private void resetInternalState() {
        dragContext = null;
        previewLayer = null;
        session = null;
        hoveredHandle = TransformHandle.NONE;
    }

    private static boolean affectsXAxis(TransformHandle handle) {
        switch (handle) {
            case SCALE_NORTH_EAST:
            case SCALE_SOUTH_EAST:
            case SCALE_NORTH_WEST:
            case SCALE_SOUTH_WEST:
            case SCALE_EAST:
            case SCALE_WEST:
                return true;
            default:
                return false;
        }
    }

    private static boolean affectsYAxis(TransformHandle handle) {
        switch (handle) {
            case SCALE_NORTH_EAST:
            case SCALE_SOUTH_EAST:
            case SCALE_NORTH_WEST:
            case SCALE_SOUTH_WEST:
            case SCALE_NORTH:
            case SCALE_SOUTH:
                return true;
            default:
                return false;
        }
    }

    private static double snapSize(double rawSize) {
        double snapped = Math.round(rawSize);
        if (Double.isNaN(snapped) || Double.isInfinite(snapped)) {
            snapped = 1.0;
        }
        if (Math.abs(snapped) < 1.0) {
            snapped = Math.copySign(1.0, snapped == 0.0 ? 1.0 : snapped);
        }
        return snapped;
    }

    private static double ensureScale(double candidate, double fallback) {
        if (Double.isNaN(candidate) || Double.isInfinite(candidate)) {
            return fallback;
        }
        if (Math.abs(candidate) < 0.01) {
            return Math.copySign(0.01, candidate == 0.0 ? 1.0 : candidate);
        }
        return candidate;
    }

    private static double[] anchorLocal(TransformHandle handle, SelectionSnapshot snapshot) {
        double width = snapshot.width();
        double height = snapshot.height();

        switch (handle) {
            case SCALE_NORTH_WEST:
                return new double[]{width, height};
            case SCALE_NORTH_EAST:
                return new double[]{0.0, height};
            case SCALE_SOUTH_EAST:
                return new double[]{0.0, 0.0};
            case SCALE_SOUTH_WEST:
                return new double[]{width, 0.0};
            case SCALE_NORTH:
                return new double[]{width / 2.0, height};
            case SCALE_EAST:
                return new double[]{0.0, height / 2.0};
            case SCALE_SOUTH:
                return new double[]{width / 2.0, 0.0};
            case SCALE_WEST:
                return new double[]{width, height / 2.0};
            default:
                return new double[]{width / 2.0, height / 2.0};
        }
    }

    private static final class DragContext {
        final TransformHandle handle;
        final boolean translation;
        final boolean rotation;
        final SelectionSnapshot snapshot;
        final TransformationState baseTransform;
        final double startCanvasX;
        final double startCanvasY;
        final double[] anchorLocal;
        final double[] anchorCanvas;
        final double pivotCanvasX;
        final double pivotCanvasY;
        final double initialAngle;

        private DragContext(TransformHandle handle,
                            boolean translation,
                            boolean rotation,
                            SelectionSnapshot snapshot,
                            TransformationState baseTransform,
                            double startCanvasX,
                            double startCanvasY,
                            double[] anchorLocal,
                            double[] anchorCanvas,
                            double pivotCanvasX,
                            double pivotCanvasY,
                            double initialAngle) {
            this.handle = handle;
            this.translation = translation;
            this.rotation = rotation;
            this.snapshot = snapshot;
            this.baseTransform = baseTransform;
            this.startCanvasX = startCanvasX;
            this.startCanvasY = startCanvasY;
            this.anchorLocal = anchorLocal;
            this.anchorCanvas = anchorCanvas;
            this.pivotCanvasX = pivotCanvasX;
            this.pivotCanvasY = pivotCanvasY;
            this.initialAngle = initialAngle;
        }

        static DragContext forTranslation(SelectionSnapshot snapshot,
                                          TransformationState baseTransform,
                                          double startCanvasX,
                                          double startCanvasY) {
            double[] pivot = TransformMath.mapLocalToCanvas(
                    snapshot.width() / 2.0,
                    snapshot.height() / 2.0,
                    snapshot,
                    baseTransform);
            return new DragContext(
                    TransformHandle.NONE,
                    true,
                    false,
                    snapshot,
                    baseTransform,
                    startCanvasX,
                    startCanvasY,
                    null,
                    null,
                    pivot[0],
                    pivot[1],
                    0.0
            );
        }

        static DragContext forHandle(TransformHandle handle,
                                     SelectionSnapshot snapshot,
                                     TransformationState baseTransform,
                                     double startCanvasX,
                                     double startCanvasY,
                                     boolean isRotationMode) {
            double[] anchorLocal = anchorLocal(handle, snapshot);
            double[] anchorCanvas = TransformMath.mapLocalToCanvas(
                    anchorLocal[0],
                    anchorLocal[1],
                    snapshot,
                    baseTransform);
            double[] pivotCanvas = TransformMath.mapLocalToCanvas(
                    snapshot.width() / 2.0,
                    snapshot.height() / 2.0,
                    snapshot,
                    baseTransform);

            double initialAngle = Math.atan2(startCanvasY - pivotCanvas[1], startCanvasX - pivotCanvas[0]);

            return new DragContext(
                    handle,
                    false,
                    isRotationMode,
                    snapshot,
                    baseTransform,
                    startCanvasX,
                    startCanvasY,
                    anchorLocal,
                    anchorCanvas,
                    pivotCanvas[0],
                    pivotCanvas[1],
                    initialAngle
            );
        }
    }
}
