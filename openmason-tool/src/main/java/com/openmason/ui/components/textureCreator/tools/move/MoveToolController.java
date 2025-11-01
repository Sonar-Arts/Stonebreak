package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.commands.move.MoveSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import imgui.ImDrawList;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.nio.DoubleBuffer;
import java.util.Objects;

/**
 * Modernised move tool implementation with non-destructive previews and precise
 * transformation maths. The controller orchestrates interaction state while
 * delegating rendering and geometry to dedicated helper classes to keep the
 * behaviour easy to maintain.
 */
public class MoveToolController implements DrawingTool {

    private static final Logger logger = LoggerFactory.getLogger(MoveToolController.class);

    private final TransformOverlayRenderer overlayRenderer = new TransformOverlayRenderer();

    private SelectionManager selectionManager;
    private SelectionManager.SelectionChangeListener selectionListener;
    private com.openmason.ui.components.textureCreator.TextureCreatorPreferences preferences;

    private MoveToolSession session;
    private TransformPreviewLayer previewLayer;
    private TransformHandle hoveredHandle = TransformHandle.NONE;
    private MoveSelectionCommand pendingCommand;

    private DragContext dragContext;
    private boolean shiftHeld = false;

    private SelectionRegion lastSelection;

    // Mouse capture state for infinite dragging
    private boolean isMouseCaptured = false;
    private double savedCursorX = 0.0;
    private double savedCursorY = 0.0;
    private long windowHandle = 0L;
    private boolean rawMouseMotionSupported = false;

    // Accumulated mouse delta for rotation (when mouse is captured)
    // Horizontal movement for rotation
    private float accumulatedDeltaX = 0.0f;

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
        } else {
            // Use transformed selection for hit testing if preview exists
            // This allows clicking on the transformed preview to continue dragging
            SelectionRegion selectionForHitTest = selection;
            if (session != null && session.transformedSelection().isPresent()) {
                selectionForHitTest = session.transformedSelection().get();
            }

            if (selectionForHitTest.contains(x, y)) {
                startTranslation(canvasX, canvasY);
            } else {
                // Clicking outside selection: stop the current drag but keep transformation preview.
                // Session, preview layer, and pending command remain alive so user can continue
                // adjusting the transformation. Only Enter commits, only ESC cancels.
                dragContext = null;
                hoveredHandle = TransformHandle.NONE;
                // Note: session, previewLayer, and pendingCommand are NOT cleared here
            }
        }
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (dragContext == null || session == null) {
            if (isMouseCaptured) {
                logger.warn("onMouseDrag: Mouse captured but no drag context!");
            }
            return;
        }

        double canvasX = x + 0.5;
        double canvasY = y + 0.5;

        TransformationState newTransform;
        if (dragContext.translation) {
            newTransform = computeTranslationTransform(canvasX, canvasY);
            session.updateTransformation(newTransform);
            previewLayer = session.createPreviewLayer();
        } else if (dragContext.rotation) {
            // Rotation updates are handled in updateMouseDelta() which is called every frame
            // This ensures infinite rotation continues even when onMouseDrag stops being called
            // (onMouseDrag only fires when canvas coords change, which stops at canvas edges)
            // Transform update happens in updateMouseDelta(), so we don't need to do it here
            return;
        } else {
            newTransform = computeScaleTransform(canvasX, canvasY);
            session.updateTransformation(newTransform);
            previewLayer = session.createPreviewLayer();
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        // Always try to release mouse capture, even if drag context is invalid
        if (isMouseCaptured) {
            releaseMouse();
        }

        if (dragContext == null || session == null) {
            return;
        }

        if (session.hasPreview()) {
            // Get skip transparent pixels preference (default true if not set)
            boolean skipTransparent = preferences != null ? preferences.isSkipTransparentPixelsOnPaste() : true;
            pendingCommand = session.createCommand(canvas, selectionManager, skipTransparent);
        }

        // IMPORTANT: Session and preview layer are NOT destroyed here.
        // They persist across multiple drag operations so the user can adjust
        // the transformation multiple times before committing with Enter or
        // canceling with ESC. This ensures only ONE hole appears at the ORIGINAL
        // selection position, not at intermediate positions.
        // Session is only destroyed in reset() (called on commit/cancel).

        // Only clear the drag context to allow starting a new drag
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
        releaseMouse();
        accumulatedDeltaX = 0.0f;

        // Destroy session and preview layer (called on commit/cancel/tool change)
        // This is the ONLY place where session should be destroyed during normal operation.
        // onMouseUp() does NOT destroy the session, allowing multiple drag adjustments.
        session = null;
        previewLayer = null;

        dragContext = null;
        hoveredHandle = TransformHandle.NONE;
        // Keep pendingCommand so CanvasPanel can still apply it if required
    }

    public void cancelAndReset(PixelCanvas canvas) {
        releaseMouse();
        accumulatedDeltaX = 0.0f;
        reset();
        pendingCommand = null;
    }

    public void setModifierKeys(boolean shiftHeld) {
        this.shiftHeld = shiftHeld;
    }

    /**
     * Updates the accumulated mouse delta for rotation tracking.
     * Call this every frame during rendering to accumulate mouse movement.
     * Uses ImGui's mouse delta which works correctly with captured mouse.
     *
     * This method also updates the rotation transform every frame when mouse is captured,
     * ensuring infinite rotation continues even when onMouseDrag stops being called.
     *
     * @param mouseDeltaX the horizontal mouse movement delta from ImGui
     */
    public void updateMouseDelta(float mouseDeltaX) {
        // Safety check: if mouse is captured but we have no drag context, release it
        if (isMouseCaptured && (dragContext == null || !dragContext.rotation)) {
            logger.warn("Mouse captured but no active rotation drag - auto-releasing");
            releaseMouse();
            accumulatedDeltaX = 0.0f;
            return;
        }

        if (dragContext != null && dragContext.rotation && isMouseCaptured) {
            // CRITICAL: Check if left mouse button is actually being held down
            // If button was released, force cleanup (handles case where onMouseUp wasn't called)
            if (!imgui.ImGui.isMouseDown(0)) {
                logger.debug("Button released during rotation - cleaning up drag context");
                releaseMouse();
                accumulatedDeltaX = 0.0f;
                dragContext = null;
                // CRITICAL: DO NOT reset session or previewLayer here!
                // Session must persist across drag operations to accumulate transformations.
                // Only Enter (commit) or ESC (cancel) should destroy the session.
                // Resetting here would lose all accumulated rotations/scales/translations.
                // The onMouseUp method handles pending command creation.
                return;
            }

            accumulatedDeltaX += mouseDeltaX;

            // CRITICAL: Update rotation transform every frame during mouse capture
            // This ensures rotation continues even when onMouseDrag isn't called
            // (which happens when canvas coords stop changing at edges)
            if (session != null) {
                TransformationState newTransform = computeRotationTransform();
                session.updateTransformation(newTransform);
                previewLayer = session.createPreviewLayer();
            }
        }
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     * This should be called from the application initialization.
     */
    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        if (windowHandle != 0L) {
            this.rawMouseMotionSupported = GLFW.glfwRawMouseMotionSupported();
            logger.info("Move tool: Window handle set successfully (handle={}, rawMotion={})",
                    windowHandle, rawMouseMotionSupported);
        } else {
            logger.error("Move tool: Invalid window handle (0) - mouse capture will not work!");
        }
    }

    /**
     * Capture the mouse cursor for infinite dragging (rotation only).
     * Hides cursor and enables unlimited mouse movement.
     */
    private void captureMouse() {
        if (windowHandle == 0L) {
            logger.warn("Cannot capture mouse: window handle not set");
            return;
        }

        if (isMouseCaptured) {
            return;  // Already captured
        }

        try {
            // Save current cursor position
            DoubleBuffer xPos = BufferUtils.createDoubleBuffer(1);
            DoubleBuffer yPos = BufferUtils.createDoubleBuffer(1);
            GLFW.glfwGetCursorPos(windowHandle, xPos, yPos);
            savedCursorX = xPos.get(0);
            savedCursorY = yPos.get(0);

            // Disable cursor (hides it and enables infinite movement)
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);

            // Verify it was set
            int newMode = GLFW.glfwGetInputMode(windowHandle, GLFW.GLFW_CURSOR);
            if (newMode != GLFW.GLFW_CURSOR_DISABLED) {
                logger.error("Failed to set cursor to DISABLED mode");
                return;
            }

            // Enable raw mouse motion if supported for better control
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
            }

            isMouseCaptured = true;
            logger.debug("Mouse captured for infinite rotation");

        } catch (Exception e) {
            logger.error("Failed to capture mouse", e);
        }
    }

    /**
     * Release mouse capture and restore cursor visibility and position.
     */
    private void releaseMouse() {
        if (!isMouseCaptured) {
            return;  // Already released, nothing to do
        }

        if (windowHandle == 0L) {
            logger.warn("Cannot release mouse: window handle is 0");
            isMouseCaptured = false;  // Reset the flag anyway
            return;
        }

        try {
            // Restore normal cursor mode
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);

            // Disable raw mouse motion
            if (rawMouseMotionSupported) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            }

            // Restore cursor position
            GLFW.glfwSetCursorPos(windowHandle, savedCursorX, savedCursorY);

            isMouseCaptured = false;
            logger.debug("Mouse capture released");

        } catch (Exception e) {
            logger.error("Failed to release mouse - forcing flag reset", e);
            isMouseCaptured = false;  // Reset flag even if GLFW calls fail
        }
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

    public void setPreferences(com.openmason.ui.components.textureCreator.TextureCreatorPreferences preferences) {
        this.preferences = preferences;
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

    public boolean hasPreviewLayer() {
        return previewLayer != null;
    }

    public TransformPreviewLayer getPreviewLayer() {
        return previewLayer;
    }

    public boolean isActive() {
        return dragContext != null || (session != null && session.hasPreview());
    }

    public TransformationState getLiveTransform() {
        return session != null ? session.transform() : TransformationState.identity();
    }

    public boolean isMouseCaptured() {
        return isMouseCaptured;
    }

    /**
     * Force release the mouse capture. Useful for emergency escape (ESC key, etc.)
     */
    public void forceReleaseMouse() {
        if (isMouseCaptured) {
            releaseMouse();
            // Also clean up drag state
            dragContext = null;
            session = null;
            previewLayer = null;
        }
    }

    private void ensureSession(PixelCanvas canvas, SelectionRegion selection) {
        // CRITICAL: Reuse existing session if selection hasn't changed
        // This preserves accumulated transformations across multiple drag operations:
        // - Rotate 45° → release → rotate 30° more → both rotations accumulate
        // - Scale 2.0x → release → scale 1.5x more → both scales accumulate
        // Session is only reset when selection changes or when committed/cancelled
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

        // CRITICAL: Pass session.transform() as the base transform
        // This ensures ALL previous transformations (rotation, scale, translation) are preserved
        // when starting a new drag operation. The session persists across drag operations until
        // the transformation is committed (Enter) or cancelled (ESC).
        // Example: Rotate 45° → release → rotate more → base starts at 45° (not 0°)
        dragContext = DragContext.forHandle(handle, session.snapshot(), session.transform(), startX, startY, isRotation);

        // Reset accumulated delta for THIS drag operation and capture mouse for infinite dragging
        // The delta is reset because it represents movement SINCE THE START OF THIS DRAG,
        // not cumulative movement across all drags (that's stored in session.transform)
        if (isRotation) {
            accumulatedDeltaX = 0.0f;
            captureMouse();
        }
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

    private TransformationState computeRotationTransform() {
        // Calculate rotation based on accumulated mouse delta
        // This uses ImGui's mouse delta which works correctly with captured mouse
        // Moving right = positive deltaX = rotate clockwise (negative degrees)
        // Moving left = negative deltaX = rotate counter-clockwise (positive degrees)

        // Get rotation speed from preferences (default 0.5 if not set)
        double rotationSpeed = preferences != null ? preferences.getRotationSpeed() : 0.5;

        // Convert accumulated horizontal movement to rotation degrees
        // Speed is in degrees per pixel of screen movement
        double rotationFromMovement = -accumulatedDeltaX * rotationSpeed;

        // Calculate the final absolute angle by ADDING to the base angle
        // This properly accumulates rotation across multiple drag operations:
        // - baseAngle contains all rotations from previous drag operations (stored in session.transform)
        // - rotationFromMovement is the rotation delta for THIS current drag operation
        // - finalAngle = previous rotations + current rotation delta
        // Example: Rotate 45° → release → rotate 30° more → finalAngle = 45° + 30° = 75°
        double baseAngle = dragContext.baseTransform.rotationDegrees();
        double finalAngle = baseAngle + rotationFromMovement;

        if (shiftHeld) {
            // Snap to 15° increments on the absolute angle
            finalAngle = Math.round(finalAngle / 15.0) * 15.0;
        } else {
            // Magnetic snapping to cardinal angles (0°, 90°, 180°, 270°) with fixed threshold
            finalAngle = snapToCardinalAngles(finalAngle, 5.0);
        }

        // withRotation() sets the absolute rotation angle (replaces old rotation)
        // This is correct because finalAngle already includes the base angle
        return dragContext.baseTransform.withRotation(finalAngle);
    }

    private TransformationState computeScaleTransform(double canvasX, double canvasY) {
        SelectionSnapshot snapshot = dragContext.snapshot;
        TransformationState base = dragContext.baseTransform;

        double width = Math.max(snapshot.width(), 1);
        double height = Math.max(snapshot.height(), 1);

        // Calculate target size in CANVAS space (not local space) to properly accumulate scales
        // The anchor stays fixed in canvas coordinates, and we measure distance from anchor to mouse
        double targetWidthCanvas = width * base.scaleX();
        double targetHeightCanvas = height * base.scaleY();

        // For handles that affect X axis, calculate canvas distance from anchor to mouse
        switch (dragContext.handle) {
            case SCALE_NORTH_EAST:
            case SCALE_EAST:
            case SCALE_SOUTH_EAST:
                // Anchor is on the left/west side, measure distance to mouse
                targetWidthCanvas = snapSize(Math.abs(canvasX - dragContext.anchorCanvas[0]));
                break;
            case SCALE_NORTH_WEST:
            case SCALE_WEST:
            case SCALE_SOUTH_WEST:
                // Anchor is on the right/east side, measure distance to mouse
                targetWidthCanvas = snapSize(Math.abs(canvasX - dragContext.anchorCanvas[0]));
                break;
            default:
                break;
        }

        // For handles that affect Y axis, calculate canvas distance from anchor to mouse
        switch (dragContext.handle) {
            case SCALE_NORTH_WEST:
            case SCALE_NORTH:
            case SCALE_NORTH_EAST:
                // Anchor is on the bottom/south side, measure distance to mouse
                targetHeightCanvas = snapSize(Math.abs(canvasY - dragContext.anchorCanvas[1]));
                break;
            case SCALE_SOUTH_WEST:
            case SCALE_SOUTH:
            case SCALE_SOUTH_EAST:
                // Anchor is on the top/north side, measure distance to mouse
                targetHeightCanvas = snapSize(Math.abs(canvasY - dragContext.anchorCanvas[1]));
                break;
            default:
                break;
        }

        boolean affectsX = affectsXAxis(dragContext.handle);
        boolean affectsY = affectsYAxis(dragContext.handle);

        if (shiftHeld && (affectsX || affectsY)) {
            double lockedPixels = Math.max(targetWidthCanvas, targetHeightCanvas);
            targetWidthCanvas = lockedPixels;
            targetHeightCanvas = lockedPixels;
            affectsX = true;
            affectsY = true;
        }

        // Convert canvas size to scale (canvas size / original snapshot size)
        double targetScaleX = affectsX ? ensureScale(targetWidthCanvas / width, base.scaleX()) : base.scaleX();
        double targetScaleY = affectsY ? ensureScale(targetHeightCanvas / height, base.scaleY()) : base.scaleY();

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

    /**
     * Snaps an angle to the nearest cardinal angle (0°, 90°, 180°, 270°) if within threshold.
     * This provides magnetic snapping behavior for precise alignment.
     *
     * @param angle the angle in degrees
     * @param threshold the snapping threshold in degrees
     * @return the snapped angle if within threshold, otherwise the original angle
     */
    private static double snapToCardinalAngles(double angle, double threshold) {
        // First normalize to [0, 360) range to check all cardinal angles uniformly
        double positive = angle % 360.0;
        if (positive < 0) {
            positive += 360.0;
        }

        // Check proximity to each cardinal angle in positive space
        double snappedPositive = positive;

        if (Math.abs(positive) < threshold || Math.abs(positive - 360.0) < threshold) {
            snappedPositive = 0.0;  // Snap to 0°/360°
        } else if (Math.abs(positive - 90.0) < threshold) {
            snappedPositive = 90.0;  // Snap to 90°
        } else if (Math.abs(positive - 180.0) < threshold) {
            snappedPositive = 180.0;  // Snap to 180°
        } else if (Math.abs(positive - 270.0) < threshold) {
            snappedPositive = 270.0;  // Snap to 270°
        } else {
            return angle;  // No snapping, return original angle
        }

        // Convert back to [-180, 180] range to match TransformationState normalization
        if (snappedPositive > 180.0) {
            return snappedPositive - 360.0;
        }
        return snappedPositive;
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
                            double pivotCanvasY) {
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
                    pivot[1]
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
                    pivotCanvas[1]
            );
        }
    }
}
