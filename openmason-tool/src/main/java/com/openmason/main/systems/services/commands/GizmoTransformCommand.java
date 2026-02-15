package com.openmason.main.systems.services.commands;

import com.openmason.main.systems.viewport.state.TransformState;
import org.joml.Vector3f;

/**
 * Command for gizmo translate/rotate/scale operations.
 *
 * <p>Stores old and new position, rotation, and scale vectors.
 * The gizmo modifies {@link TransformState} (a model-level transform
 * applied at render time), not per-vertex positions.
 *
 * <p>Not mergeable — each gizmo drag is a discrete operation.
 */
public final class GizmoTransformCommand implements ModelCommand {

    private final Vector3f oldPosition;
    private final Vector3f oldRotation;
    private final Vector3f oldScale;
    private final Vector3f newPosition;
    private final Vector3f newRotation;
    private final Vector3f newScale;
    private final String description;
    private final TransformState transformState;

    public GizmoTransformCommand(Vector3f oldPosition, Vector3f oldRotation, Vector3f oldScale,
                                 Vector3f newPosition, Vector3f newRotation, Vector3f newScale,
                                 String description,
                                 TransformState transformState) {
        this.oldPosition = new Vector3f(oldPosition);
        this.oldRotation = new Vector3f(oldRotation);
        this.oldScale = new Vector3f(oldScale);
        this.newPosition = new Vector3f(newPosition);
        this.newRotation = new Vector3f(newRotation);
        this.newScale = new Vector3f(newScale);
        this.description = description;
        this.transformState = transformState;
    }

    @Override
    public void execute() {
        transformState.setPosition(newPosition.x, newPosition.y, newPosition.z);
        transformState.setRotation(newRotation.x, newRotation.y, newRotation.z);
        transformState.setScale(newScale.x, newScale.y, newScale.z);
    }

    @Override
    public void undo() {
        transformState.setPosition(oldPosition.x, oldPosition.y, oldPosition.z);
        transformState.setRotation(oldRotation.x, oldRotation.y, oldRotation.z);
        transformState.setScale(oldScale.x, oldScale.y, oldScale.z);
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("GizmoTransformCommand is not mergeable");
    }

    // ── Factory methods ─────────────────────────────────────────────────

    public static GizmoTransformCommand translate(Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                                  Vector3f newPos, Vector3f newRot, Vector3f newScale,
                                                  TransformState transformState) {
        return new GizmoTransformCommand(oldPos, oldRot, oldScale, newPos, newRot, newScale,
                "Gizmo Translate", transformState);
    }

    public static GizmoTransformCommand rotate(Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                               Vector3f newPos, Vector3f newRot, Vector3f newScale,
                                               TransformState transformState) {
        return new GizmoTransformCommand(oldPos, oldRot, oldScale, newPos, newRot, newScale,
                "Gizmo Rotate", transformState);
    }

    public static GizmoTransformCommand scale(Vector3f oldPos, Vector3f oldRot, Vector3f oldScale,
                                              Vector3f newPos, Vector3f newRot, Vector3f newScale,
                                              TransformState transformState) {
        return new GizmoTransformCommand(oldPos, oldRot, oldScale, newPos, newRot, newScale,
                "Gizmo Scale", transformState);
    }
}
