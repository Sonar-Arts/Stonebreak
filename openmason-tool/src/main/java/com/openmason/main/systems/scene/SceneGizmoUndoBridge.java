package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.gizmo.TransformUndoSink;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.services.commands.GizmoTransformCommand;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * Records a finished scene gizmo drag as an undo entry.
 *
 * <p>Reuses the model editor's {@link GizmoTransformCommand} and
 * {@link ModelCommandHistory} verbatim: a scene instance's transform is a
 * {@code TransformState}, which is exactly what that command applies and reverts, so
 * nothing about undoing a move needed reinventing.
 *
 * <p>The target is resolved at commit time rather than held, because the selection — and
 * therefore which instance the gizmo drives — changes between drags.
 */
public class SceneGizmoUndoBridge implements TransformUndoSink {

    private final ModelCommandHistory history;
    private final Supplier<ModelInstance> selectedInstance;

    public SceneGizmoUndoBridge(ModelCommandHistory history, Supplier<ModelInstance> selectedInstance) {
        this.history = java.util.Objects.requireNonNull(history, "history");
        this.selectedInstance = java.util.Objects.requireNonNull(selectedInstance, "selectedInstance");
    }

    @Override
    public void onTransformCommitted(Mode mode,
                                     Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                     Vector3f newPos, Vector3f newRot, Vector3f newScale) {
        ModelInstance instance = selectedInstance.get();
        if (instance == null) {
            return;
        }

        GizmoTransformCommand command = switch (mode) {
            case TRANSLATE -> GizmoTransformCommand.translate(
                    oldPos, oldRot, oldScale, newPos, newRot, newScale, instance.transform());
            case ROTATE -> GizmoTransformCommand.rotate(
                    oldPos, oldRot, oldScale, newPos, newRot, newScale, instance.transform());
            case SCALE -> GizmoTransformCommand.scale(
                    oldPos, oldRot, oldScale, newPos, newRot, newScale, instance.transform());
        };
        history.pushCompleted(command);
    }
}
