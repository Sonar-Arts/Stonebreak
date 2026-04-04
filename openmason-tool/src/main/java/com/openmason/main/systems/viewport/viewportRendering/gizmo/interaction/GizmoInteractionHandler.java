package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Coordinates mouse interaction with the transform gizmo.
 * Delegates hover detection to {@link GizmoHoverDetector}, drag computation
 * to {@link DragHandler}, transform application to {@link TransformApplier},
 * and undo recording to {@link UndoRedoRecorder}.
 */
public class GizmoInteractionHandler {

    private final GizmoState gizmoState;
    private final TransformState transformState;
    private ViewportUIState viewportState;

    // Transform target abstraction
    private ITransformTarget transformTarget;

    // Delegated subsystems
    private final GizmoHoverDetector hoverDetector;
    private final DragHandler dragHandler;
    private final TransformApplier transformApplier;
    private final UndoRedoRecorder undoRedoRecorder;

    // Cached camera matrices (shared with subsystems)
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private int viewportWidth = 1;
    private int viewportHeight = 1;

    public GizmoInteractionHandler(GizmoState gizmoState, TransformState transformState,
                                   ViewportUIState viewportState) {
        if (gizmoState == null) {
            throw new IllegalArgumentException("GizmoState cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.gizmoState = gizmoState;
        this.transformState = transformState;
        this.viewportState = viewportState;

        this.hoverDetector = new GizmoHoverDetector();
        this.transformApplier = new TransformApplier(transformState);
        this.dragHandler = new DragHandler(gizmoState, transformState, transformApplier);
        this.undoRedoRecorder = new UndoRedoRecorder();
    }

    /**
     * Set the command history for undo/redo recording.
     */
    public void setCommandHistory(ModelCommandHistory commandHistory) {
        undoRedoRecorder.setCommandHistory(commandHistory);
    }

    /**
     * Set the transform target for gizmo operations.
     */
    public void setTransformTarget(ITransformTarget target) {
        this.transformTarget = target;
    }

    /**
     * Get the currently active transform target.
     * Returns the custom target if set and active, otherwise null.
     */
    public ITransformTarget getActiveTransformTarget() {
        if (transformTarget != null && transformTarget.isActive()) {
            return transformTarget;
        }
        return null;
    }

    /**
     * Update viewport state for snapping settings.
     */
    public void updateViewportState(ViewportUIState viewportState) {
        this.viewportState = viewportState;
    }

    /**
     * Sets the gizmo's computed world-space center position (used as rotation pivot).
     */
    public void setGizmoWorldCenter(Vector3f center) {
        dragHandler.setGizmoWorldCenter(center);
    }

    /**
     * Updates the camera matrices used for raycasting.
     * Should be called each frame before processing input.
     */
    public void updateCamera(Matrix4f view, Matrix4f projection, int width, int height) {
        if (view == null || projection == null) {
            throw new IllegalArgumentException("Matrices cannot be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }

        this.viewMatrix.set(view);
        this.projectionMatrix.set(projection);
        this.viewportWidth = width;
        this.viewportHeight = height;

        dragHandler.updateCamera(view, projection, width, height);
    }

    /**
     * Handles mouse movement for hover detection or drag operations.
     */
    public void handleMouseMove(float mouseX, float mouseY, List<GizmoPart> gizmoParts) {
        if (gizmoParts == null) {
            throw new IllegalArgumentException("GizmoParts cannot be null");
        }

        if (!gizmoState.isEnabled()) {
            return;
        }

        if (gizmoState.isDragging()) {
            dragHandler.updateState(viewportState, transformTarget);
            dragHandler.handleDrag(mouseX, mouseY, getActiveTransformTarget());
        } else {
            GizmoPart hoveredPart = hoverDetector.detectHover(
                    mouseX, mouseY, viewportWidth, viewportHeight,
                    viewMatrix, projectionMatrix, gizmoParts
            );
            gizmoState.setHoveredPart(hoveredPart);
        }
    }

    /**
     * Handles mouse press to start a drag operation.
     *
     * @return true if a gizmo part was clicked, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        if (!gizmoState.isEnabled()) {
            return false;
        }

        GizmoPart hoveredPart = gizmoState.getHoveredPart();
        if (hoveredPart != null) {
            ITransformTarget target = getActiveTransformTarget();
            Vector3f position;
            Vector3f rotation;
            Vector3f scale;

            if (target != null) {
                if (target instanceof PartTransformTarget partTarget) {
                    if (partTarget.areAllSelectedPartsLocked()) {
                        return false;
                    }
                    partTarget.snapshotDragStart();
                }
                position = target.getPosition();
                rotation = target.getRotation();
                scale = target.getScale();
            } else {
                if (transformTarget instanceof PartTransformTarget partTarget) {
                    partTarget.snapshotAllPartsForModelDrag();
                }
                position = new Vector3f(
                        transformState.getPositionX(),
                        transformState.getPositionY(),
                        transformState.getPositionZ()
                );
                rotation = new Vector3f(
                        transformState.getRotationX(),
                        transformState.getRotationY(),
                        transformState.getRotationZ()
                );
                scale = new Vector3f(
                        transformState.getScaleX(),
                        transformState.getScaleY(),
                        transformState.getScaleZ()
                );
            }

            gizmoState.startDrag(hoveredPart, mouseX, mouseY, position, rotation, scale);
            return true;
        }

        return false;
    }

    /**
     * Handles mouse release to end a drag operation.
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!gizmoState.isEnabled()) {
            return;
        }

        if (gizmoState.isDragging()) {
            ITransformTarget activeTarget = getActiveTransformTarget();
            undoRedoRecorder.recordIfChanged(gizmoState, activeTarget, transformState);

            // Clear multi-part drag snapshots
            if (activeTarget instanceof PartTransformTarget partTarget) {
                partTarget.clearDragSnapshots();
            }

            gizmoState.endDrag();
        }
    }
}
