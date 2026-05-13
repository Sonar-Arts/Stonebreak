package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Gizmo transform target for the currently-selected bone in a {@link BoneStore}.
 *
 * <p>Treats the bone's local {@code pos} / {@code rot} fields as the editable values.
 * Bone {@code origin} is the rest pivot in parent space and stays untouched — drags
 * adjust the {@code pos} offset so the joint visibly moves while the rest pose is
 * preserved. Scale is intentionally a no-op since bones are pure transform nodes
 * with no geometric scale of their own.
 *
 * <p>Single responsibility: bridge the gizmo interaction layer to {@link BoneStore}.
 * Selection is read from {@link BoneStore#getSelectedBoneId()} so the same handle
 * always works whether the user picks a bone in the hierarchy or via some future
 * viewport picker.
 */
public class BoneTransformTarget implements ITransformTarget {

    private final BoneStore boneStore;
    private final TransformState transformState;

    public BoneTransformTarget(BoneStore boneStore, TransformState transformState) {
        this.boneStore = boneStore;
        this.transformState = transformState;
    }

    @Override
    public Vector3f getPosition() {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return new Vector3f();
        return new Vector3f(b.posX(), b.posY(), b.posZ());
    }

    @Override
    public Vector3f getRotation() {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return new Vector3f();
        return new Vector3f(b.rotX(), b.rotY(), b.rotZ());
    }

    @Override
    public Vector3f getScale() {
        return new Vector3f(1, 1, 1);
    }

    @Override
    public void setPosition(float x, float y, float z) {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return;
        boneStore.put(new OMOFormat.BoneEntry(
                b.id(), b.name(), b.parentBoneId(),
                b.originX(), b.originY(), b.originZ(),
                x, y, z,
                b.rotX(), b.rotY(), b.rotZ(),
                b.endpointX(), b.endpointY(), b.endpointZ()
        ));
    }

    @Override
    public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
        if (snap && snapIncrement > 0) {
            x = Math.round(x / snapIncrement) * snapIncrement;
            y = Math.round(y / snapIncrement) * snapIncrement;
            z = Math.round(z / snapIncrement) * snapIncrement;
        }
        setPosition(x, y, z);
    }

    @Override
    public void setRotation(float x, float y, float z) {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return;
        boneStore.put(new OMOFormat.BoneEntry(
                b.id(), b.name(), b.parentBoneId(),
                b.originX(), b.originY(), b.originZ(),
                b.posX(), b.posY(), b.posZ(),
                x, y, z,
                b.endpointX(), b.endpointY(), b.endpointZ()
        ));
    }

    @Override
    public void setScale(float x, float y, float z) {
        // Bones do not carry geometric scale.
    }

    @Override
    public Vector3f getWorldCenter() {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return new Vector3f();
        Vector3f jointWorld = boneStore.getJointWorldPosition(b.id());
        if (jointWorld == null) return new Vector3f();

        Matrix4f modelTransform = transformState.getTransformMatrix();
        return modelTransform.transformPosition(jointWorld);
    }

    @Override
    public boolean isActive() {
        return selected() != null;
    }

    @Override
    public String getTargetName() {
        OMOFormat.BoneEntry b = selected();
        return b == null ? "No Bone Selected" : "Bone: " + b.name();
    }

    private OMOFormat.BoneEntry selected() {
        String id = boneStore.getSelectedBoneId();
        return id == null ? null : boneStore.getById(id);
    }
}
