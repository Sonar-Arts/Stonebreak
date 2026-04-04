package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.state.TransformState;
import org.joml.Vector3f;

/**
 * Applies computed transform values to the appropriate target.
 * Handles the three-way dispatch: active part target, model-level part delta,
 * or direct TransformState update.
 */
public class TransformApplier {

    private final TransformState transformState;

    public TransformApplier(TransformState transformState) {
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }
        this.transformState = transformState;
    }

    /**
     * Applies a position transform to the correct target.
     *
     * @param newPos          The new position value
     * @param activeTarget    The active part target (may be null)
     * @param transformTarget The configured transform target (may be a PartTransformTarget even when inactive)
     * @param dragStartPos    The position at drag start (for delta computation on model-level part moves)
     * @param viewportState   Viewport state for grid snapping (may be null)
     */
    public void applyPosition(Vector3f newPos, ITransformTarget activeTarget,
                              ITransformTarget transformTarget, Vector3f dragStartPos,
                              ViewportUIState viewportState) {
        if (activeTarget != null) {
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                activeTarget.setPosition(newPos.x, newPos.y, newPos.z,
                        true, viewportState.getGridSnappingIncrement().get());
            } else {
                activeTarget.setPosition(newPos.x, newPos.y, newPos.z);
            }
        } else if (transformTarget instanceof PartTransformTarget partTarget) {
            Vector3f delta = new Vector3f(newPos).sub(dragStartPos);
            partTarget.applyTranslationDeltaToUnlocked(delta);
        } else {
            if (viewportState != null && viewportState.getGridSnappingEnabled().get()) {
                transformState.setPosition(newPos.x, newPos.y, newPos.z,
                        true, viewportState.getGridSnappingIncrement().get());
            } else {
                transformState.setPosition(newPos.x, newPos.y, newPos.z);
            }
        }
    }

    /**
     * Applies a rotation transform to the correct target.
     */
    public void applyRotation(Vector3f newRot, ITransformTarget activeTarget,
                              ITransformTarget transformTarget) {
        if (activeTarget != null) {
            activeTarget.setRotation(newRot.x, newRot.y, newRot.z);
        } else if (transformTarget instanceof PartTransformTarget partTarget) {
            partTarget.applyRotationToUnlocked(newRot);
        } else {
            transformState.setRotation(newRot.x, newRot.y, newRot.z);
        }
    }

    /**
     * Applies a scale transform to the correct target.
     */
    public void applyScale(float newScaleX, float newScaleY, float newScaleZ,
                           ITransformTarget activeTarget, ITransformTarget transformTarget) {
        if (activeTarget != null) {
            activeTarget.setScale(newScaleX, newScaleY, newScaleZ);
        } else if (transformTarget instanceof PartTransformTarget partTarget) {
            partTarget.applyScaleToUnlocked(newScaleX, newScaleY, newScaleZ);
        } else {
            transformState.setScale(newScaleX, newScaleY, newScaleZ);
        }
    }
}
