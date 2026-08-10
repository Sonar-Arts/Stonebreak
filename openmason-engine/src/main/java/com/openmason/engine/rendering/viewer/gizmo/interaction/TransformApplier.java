package com.openmason.engine.rendering.viewer.gizmo.interaction;

import com.openmason.engine.rendering.viewer.gizmo.SnapSettings;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.engine.rendering.viewer.math.SnappingUtil;
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
     * @param transformTarget The configured transform target; when inactive but
     *                        {@code supportsGroupFallback()}, it absorbs the drag as a group move
     * @param dragStartPos    The position at drag start (for delta computation on model-level part moves)
     * @param snapSettings   Viewport state for grid snapping (may be null)
     */
    public void applyPosition(Vector3f newPos, ITransformTarget activeTarget,
                              ITransformTarget transformTarget, Vector3f dragStartPos,
                              SnapSettings snapSettings) {
        if (activeTarget != null) {
            if (snapSettings != null && snapSettings.isSnapEnabled()) {
                activeTarget.setPosition(newPos.x, newPos.y, newPos.z,
                        true, snapSettings.getSnapIncrement());
            } else {
                activeTarget.setPosition(newPos.x, newPos.y, newPos.z);
            }
        } else if (transformTarget != null && transformTarget.supportsGroupFallback()) {
            Vector3f delta = new Vector3f(newPos).sub(dragStartPos);
            // Snap the group delta so unselected model-level part drags honor
            // grid snapping just like selected-part and model-transform paths.
            // Snapping the delta (vs. each part's absolute position) preserves
            // group cohesion — every part moves by the same grid-aligned offset.
            if (snapSettings != null && snapSettings.isSnapEnabled()) {
                float inc = snapSettings.getSnapIncrement();
                delta.set(
                        SnappingUtil.snapToGrid(delta.x, inc),
                        SnappingUtil.snapToGrid(delta.y, inc),
                        SnappingUtil.snapToGrid(delta.z, inc)
                );
            }
            transformTarget.applyGroupTranslationDelta(delta);
        } else {
            if (snapSettings != null && snapSettings.isSnapEnabled()) {
                transformState.setPosition(newPos.x, newPos.y, newPos.z,
                        true, snapSettings.getSnapIncrement());
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
        } else if (transformTarget != null && transformTarget.supportsGroupFallback()) {
            transformTarget.applyGroupRotation(newRot);
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
        } else if (transformTarget != null && transformTarget.supportsGroupFallback()) {
            transformTarget.applyGroupScale(newScaleX, newScaleY, newScaleZ);
        } else {
            transformState.setScale(newScaleX, newScaleY, newScaleZ);
        }
    }
}
