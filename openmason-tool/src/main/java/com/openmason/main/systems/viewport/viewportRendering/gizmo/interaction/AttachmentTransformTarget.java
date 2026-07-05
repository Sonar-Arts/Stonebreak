package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.skeleton.AttachmentStore;
import com.openmason.main.systems.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Gizmo transform target for the currently-selected attachment point (socket)
 * in an {@link AttachmentStore}.
 *
 * <p>Edits the socket's {@code pos} / {@code rot} / {@code scale} fields
 * directly — these are authored in rest-pose model space, so no parent-frame
 * conversion is needed. Scale is a real edit: it scales whatever model is
 * attached to the socket at runtime.
 *
 * <p>Single responsibility: bridge the gizmo interaction layer to
 * {@link AttachmentStore}. Selection is read from
 * {@link AttachmentStore#getSelectedAttachmentId()}.
 */
public class AttachmentTransformTarget implements ITransformTarget {

    private final AttachmentStore attachmentStore;
    private final TransformState transformState;

    public AttachmentTransformTarget(AttachmentStore attachmentStore, TransformState transformState) {
        this.attachmentStore = attachmentStore;
        this.transformState = transformState;
    }

    @Override
    public Vector3f getPosition() {
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return new Vector3f();
        return new Vector3f(p.posX(), p.posY(), p.posZ());
    }

    @Override
    public Vector3f getRotation() {
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return new Vector3f();
        return new Vector3f(p.rotX(), p.rotY(), p.rotZ());
    }

    @Override
    public Vector3f getScale() {
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return new Vector3f(1, 1, 1);
        return new Vector3f(p.scaleX(), p.scaleY(), p.scaleZ());
    }

    @Override
    public void setPosition(float x, float y, float z) {
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return;
        attachmentStore.put(new OMOFormat.AttachmentPointEntry(
                p.id(), p.name(), p.parentPartId(), p.parentPartName(),
                x, y, z,
                p.rotX(), p.rotY(), p.rotZ(),
                p.scaleX(), p.scaleY(), p.scaleZ()
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
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return;
        attachmentStore.put(new OMOFormat.AttachmentPointEntry(
                p.id(), p.name(), p.parentPartId(), p.parentPartName(),
                p.posX(), p.posY(), p.posZ(),
                x, y, z,
                p.scaleX(), p.scaleY(), p.scaleZ()
        ));
    }

    @Override
    public void setScale(float x, float y, float z) {
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return;
        attachmentStore.put(new OMOFormat.AttachmentPointEntry(
                p.id(), p.name(), p.parentPartId(), p.parentPartName(),
                p.posX(), p.posY(), p.posZ(),
                p.rotX(), p.rotY(), p.rotZ(),
                x, y, z
        ));
    }

    @Override
    public Vector3f getWorldCenter() {
        OMOFormat.AttachmentPointEntry p = selected();
        if (p == null) return new Vector3f();
        Vector3f modelSpacePos = attachmentStore.getWorldPosition(p.id());
        if (modelSpacePos == null) return new Vector3f();

        Matrix4f modelTransform = transformState.getTransformMatrix();
        return modelTransform.transformPosition(modelSpacePos);
    }

    @Override
    public boolean isActive() {
        return selected() != null;
    }

    @Override
    public String getTargetName() {
        OMOFormat.AttachmentPointEntry p = selected();
        return p == null ? "No Socket Selected" : "Socket: " + p.name();
    }

    private OMOFormat.AttachmentPointEntry selected() {
        String id = attachmentStore.getSelectedAttachmentId();
        return id == null ? null : attachmentStore.getById(id);
    }
}
