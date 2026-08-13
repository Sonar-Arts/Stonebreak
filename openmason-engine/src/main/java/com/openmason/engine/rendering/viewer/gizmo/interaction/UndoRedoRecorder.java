package com.openmason.engine.rendering.viewer.gizmo.interaction;

import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import org.joml.Vector3f;

/**
 * Detects whether a gizmo drag actually changed anything and, if so, reports it to a
 * {@link TransformUndoSink}.
 *
 * <p>Model-level drags (no active target) are always reported. Drags of an active
 * {@link ITransformTarget} are reported only when the target opts in via
 * {@link ITransformTarget#recordsDragsForUndo()} — a per-target property, because one
 * gizmo swaps between targets whose undo stories differ: a scene's instance target has no
 * other undo mechanism and wants its drags recorded, while the editor's part/bone/socket
 * targets must not be reported to a sink that writes the model-level transform. Building
 * the actual undo command is the host's job (see {@code GizmoUndoBridge}) — this class
 * deliberately no longer knows what an undo entry is, so the gizmo can be reused by a
 * host with a different history.
 */
public class UndoRedoRecorder {

    /** Never null, so the change-detection path needs no guard. */
    private TransformUndoSink undoSink = TransformUndoSink.NONE;

    /** Null resets to {@link TransformUndoSink#NONE}, i.e. recording disabled. */
    public void setUndoSink(TransformUndoSink undoSink) {
        this.undoSink = undoSink != null ? undoSink : TransformUndoSink.NONE;
    }

    /**
     * Records a transform operation if the values changed during the drag.
     * Model-level drags are always considered; active-target drags only when the target
     * opts in via {@link ITransformTarget#recordsDragsForUndo()}.
     *
     * @param gizmoState     The gizmo state containing drag start snapshots
     * @param activeTarget   The active transform target (null = model-level)
     * @param transformState The transform state for reading current model values
     */
    public void recordIfChanged(GizmoState gizmoState, ITransformTarget activeTarget,
                                TransformState transformState) {
        if (activeTarget != null && !activeTarget.recordsDragsForUndo()) {
            // This target's drags are not undoable through the host's sink — reporting
            // them would record into a transform the drag never touched.
            return;
        }

        Vector3f oldPos = gizmoState.getDragStartObjectPos();
        Vector3f oldRot = gizmoState.getDragStartObjectRotation();
        Vector3f oldScale = gizmoState.getDragStartObjectScale();

        // Read the result from whatever actually moved.
        Vector3f newPos;
        Vector3f newRot;
        Vector3f newScale;
        if (activeTarget != null) {
            newPos = activeTarget.getPosition();
            newRot = activeTarget.getRotation();
            newScale = activeTarget.getScale();
        } else {
            newPos = new Vector3f(
                    transformState.getPositionX(),
                    transformState.getPositionY(),
                    transformState.getPositionZ()
            );
            newRot = new Vector3f(
                    transformState.getRotationX(),
                    transformState.getRotationY(),
                    transformState.getRotationZ()
            );
            newScale = new Vector3f(
                    transformState.getScaleX(),
                    transformState.getScaleY(),
                    transformState.getScaleZ()
            );
        }

        boolean changed = !oldPos.equals(newPos, 0.0001f)
                || !oldRot.equals(newRot, 0.0001f)
                || !oldScale.equals(newScale, 0.0001f);
        if (!changed) {
            return;
        }

        TransformUndoSink.Mode mode = switch (gizmoState.getCurrentMode()) {
            case TRANSLATE -> TransformUndoSink.Mode.TRANSLATE;
            case ROTATE -> TransformUndoSink.Mode.ROTATE;
            case SCALE -> TransformUndoSink.Mode.SCALE;
        };
        undoSink.onTransformCommitted(mode, oldPos, oldRot, oldScale, newPos, newRot, newScale);
    }
}
