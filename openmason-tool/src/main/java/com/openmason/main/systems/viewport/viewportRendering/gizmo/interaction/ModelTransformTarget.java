package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.main.systems.viewport.state.TransformState;
import org.joml.Vector3f;

/**
 * Transform target for the whole model (default behavior).
 * Wraps the existing {@link TransformState} so the gizmo operates on
 * the model's position/rotation/scale as before.
 */
public class ModelTransformTarget implements ITransformTarget {

    private final TransformState transformState;
    private ModelBounds modelBounds = ModelBounds.EMPTY;

    public ModelTransformTarget(TransformState transformState) {
        this.transformState = transformState;
    }

    /**
     * Update the model bounds used for center calculation.
     *
     * @param bounds Current model bounds
     */
    public void updateModelBounds(ModelBounds bounds) {
        this.modelBounds = bounds != null ? bounds : ModelBounds.EMPTY;
    }

    @Override
    public Vector3f getPosition() {
        return new Vector3f(
                transformState.getPositionX(),
                transformState.getPositionY(),
                transformState.getPositionZ()
        );
    }

    @Override
    public Vector3f getRotation() {
        return new Vector3f(
                transformState.getRotationX(),
                transformState.getRotationY(),
                transformState.getRotationZ()
        );
    }

    @Override
    public Vector3f getScale() {
        return new Vector3f(
                transformState.getScaleX(),
                transformState.getScaleY(),
                transformState.getScaleZ()
        );
    }

    @Override
    public void setPosition(float x, float y, float z) {
        transformState.setPosition(x, y, z);
    }

    @Override
    public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
        transformState.setPosition(x, y, z, snap, snapIncrement);
    }

    @Override
    public void setRotation(float x, float y, float z) {
        transformState.setRotation(x, y, z);
    }

    @Override
    public void setScale(float x, float y, float z) {
        transformState.setScale(x, y, z);
    }

    @Override
    public Vector3f getWorldCenter() {
        Vector3f boundsCenter = modelBounds.center();
        if (boundsCenter.lengthSquared() < 0.0001f) {
            return getPosition();
        }
        return transformState.getTransformMatrix()
                .transformPosition(new Vector3f(boundsCenter));
    }

    @Override
    public boolean isActive() {
        return true; // Model target is always active
    }

    @Override
    public String getTargetName() {
        return "Model";
    }
}
