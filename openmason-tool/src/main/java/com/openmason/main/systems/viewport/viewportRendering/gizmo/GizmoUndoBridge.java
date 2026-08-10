package com.openmason.main.systems.viewport.viewportRendering.gizmo;

import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;

import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.main.systems.services.commands.GizmoTransformCommand;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import org.joml.Vector3f;

/**
 * Turns a completed gizmo drag into a model-editor undo entry.
 *
 * <p>This is the model editor's half of the {@link TransformUndoSink} seam: the gizmo
 * reports before/after values and knows nothing about {@link ModelCommandHistory} or
 * {@link GizmoTransformCommand}, both of which are editor concepts. Lifted verbatim from
 * the command-building tail of {@code UndoRedoRecorder}.
 */
public class GizmoUndoBridge implements TransformUndoSink {

    private final TransformState transformState;
    private ModelCommandHistory commandHistory;

    public GizmoUndoBridge(TransformState transformState) {
        this.transformState = java.util.Objects.requireNonNull(transformState, "transformState");
    }

    /** Null disables recording, matching the old recorder's behaviour. */
    public void setCommandHistory(ModelCommandHistory commandHistory) {
        this.commandHistory = commandHistory;
    }

    @Override
    public void onTransformCommitted(Mode mode,
                                     Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                     Vector3f newPos, Vector3f newRot, Vector3f newScale) {
        if (commandHistory == null) {
            return;
        }

        GizmoTransformCommand command = switch (mode) {
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
