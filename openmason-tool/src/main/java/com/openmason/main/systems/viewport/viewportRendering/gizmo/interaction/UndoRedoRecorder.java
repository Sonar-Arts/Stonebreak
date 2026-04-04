package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.services.commands.GizmoTransformCommand;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import org.joml.Vector3f;

/**
 * Records gizmo transform operations as undo/redo commands.
 * Only records model-level transforms; part-level undo is handled separately
 * by the ModelPartManager system.
 */
public class UndoRedoRecorder {

    private ModelCommandHistory commandHistory;

    /**
     * Sets the command history for recording undo/redo commands.
     */
    public void setCommandHistory(ModelCommandHistory commandHistory) {
        this.commandHistory = commandHistory;
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
        if (commandHistory == null) {
            return;
        }

        Vector3f oldPos = gizmoState.getDragStartObjectPos();
        Vector3f oldRot = gizmoState.getDragStartObjectRotation();
        Vector3f oldScale = gizmoState.getDragStartObjectScale();

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

        // Only record undo for model-level transforms (part undo is separate)
        if (activeTarget == null &&
                (!oldPos.equals(newPos, 0.0001f)
                        || !oldRot.equals(newRot, 0.0001f)
                        || !oldScale.equals(newScale, 0.0001f))) {

            GizmoTransformCommand command = switch (gizmoState.getCurrentMode()) {
                case TRANSLATE -> GizmoTransformCommand.translate(
                        oldPos, oldRot, oldScale, newPos, newRot, newScale, transformState);
                case ROTATE -> GizmoTransformCommand.rotate(
                        oldPos, oldRot, oldScale, newPos, newRot, newScale, transformState);
                case SCALE -> GizmoTransformCommand.scale(
                        oldPos, oldRot, oldScale, newPos, newRot, newScale, transformState);
            };
            commandHistory.pushCompleted(command);
        }
    }
}
