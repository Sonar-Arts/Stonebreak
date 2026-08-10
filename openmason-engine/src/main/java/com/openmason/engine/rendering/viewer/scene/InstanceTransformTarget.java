package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.rendering.viewer.gizmo.interaction.ITransformTarget;
import org.joml.Vector3f;

/**
 * Lets the gizmo drive a scene instance.
 *
 * <p>The fifth implementation of {@link ITransformTarget}, alongside the editor's part,
 * bone, socket and model targets — evidence the seam generalized: transforming a whole
 * placed model needed no change to the gizmo at all.
 */
public final class InstanceTransformTarget implements ITransformTarget {

    private ModelInstance instance;

    public void setInstance(ModelInstance instance) {
        this.instance = instance;
    }

    public ModelInstance instance() {
        return instance;
    }

    @Override
    public Vector3f getPosition() {
        if (instance == null) return new Vector3f();
        var t = instance.transform();
        return new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ());
    }

    @Override
    public Vector3f getRotation() {
        if (instance == null) return new Vector3f();
        var t = instance.transform();
        return new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ());
    }

    @Override
    public Vector3f getScale() {
        if (instance == null) return new Vector3f(1, 1, 1);
        var t = instance.transform();
        return new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ());
    }

    @Override
    public void setPosition(float x, float y, float z) {
        if (instance == null || instance.isLocked()) return;
        instance.transform().setPosition(x, y, z);
    }

    @Override
    public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
        if (instance == null || instance.isLocked()) return;
        instance.transform().setPosition(x, y, z, snap, snapIncrement);
    }

    @Override
    public void setRotation(float x, float y, float z) {
        if (instance == null || instance.isLocked()) return;
        instance.transform().setRotation(x, y, z);
    }

    @Override
    public void setScale(float x, float y, float z) {
        if (instance == null || instance.isLocked()) return;
        instance.transform().setScale(x, y, z);
    }

    @Override
    public Vector3f getWorldCenter() {
        return instance == null ? new Vector3f() : instance.worldBounds().center();
    }

    @Override
    public boolean isActive() {
        return instance != null;
    }

    @Override
    public boolean isLocked() {
        return instance != null && instance.isLocked();
    }

    @Override
    public String getTargetName() {
        return instance == null ? "No selection" : instance.name();
    }
}
