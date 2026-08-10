package com.openmason.engine.rendering.viewer.gizmo.interaction;

import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import org.joml.Vector3f;

/**
 * Detects whether a gizmo drag actually changed anything and, if so, reports it to a
 * {@link TransformUndoSink}.
 *
 * <p>Only model-level transforms are reported; part-level undo is handled separately by
 * the ModelPartManager system. Building the actual undo command is the host's job (see
 * {@code GizmoUndoBridge}) — this class deliberately no longer knows what an undo entry
 * is, so the gizmo can be reused by a host with a different history.
 */
public class UndoRedoRecorder {

    /** Never null, so the change-detection path needs no guard. */
    private TransformUndoSink undoSink = TransformUndoSink.NONE;

    /**
     * Whether drags of an <em>active</em> target are reported.
     *
     * <p>Off by default, because in the model editor an active target means a part, and
     * part edits are undone by the part system — reporting them too would produce two
     * undo entries for one drag. A host whose targets have no other undo mechanism (a
     * scene, whose targets are placed instances) turns this on.
     */
    private boolean recordActiveTargets = false;

    /** Null resets to {@link TransformUndoSink#NONE}, i.e. recording disabled. */
    public void setUndoSink(TransformUndoSink undoSink) {
        this.undoSink = undoSink != null ? undoSink : TransformUndoSink.NONE;
    }

    /** See {@link #recordActiveTargets}. */
    public void setRecordActiveTargets(boolean recordActiveTargets) {
        this.recordActiveTargets = recordActiveTargets;
    }

    /**
     * Records a transform operation if the values changed during the drag.
     * Only records for model-level transforms (when activeTarget is null).
     *
     * @param gizmoState     The gizmo state containing drag start snapshots
     * @param activeTarget   The active part target (null = model-level)
     * @param transformState The transform state for reading current model values
     */
    public void recordIfChanged(GizmoState gizmoState, ITransformTarget activeTarget,
                                TransformState transformState) {
        if (activeTarget != null && !recordActiveTargets) {
            // Part-level drags are undone by the part system; reporting them here as well
            // would give one drag two undo entries.
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
