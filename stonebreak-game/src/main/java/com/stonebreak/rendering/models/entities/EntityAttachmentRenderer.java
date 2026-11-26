package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.sbe.AttachmentVisual;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws what is mounted on a host entity's attachment sockets
 * ({@link com.stonebreak.mobs.sbe.EntityAttachments}) — e.g. an equipped hat or a held weapon —
 * posed at each socket's world frame for the host's current animation state,
 * so attachments track walk/graze/head-turn exactly.
 */
final class EntityAttachmentRenderer {
    /** Flat fallback tint for attached models whose OMO carries no materials. */
    private static final Vector4f ATTACHMENT_FALLBACK_COLOR = new Vector4f(0.85f, 0.85f, 0.85f, 1f);

    private final SbeEntityRenderer sbeEntityRenderer;
    private final SocketItemQueue socketItems;
    /** The host's posed joints, shared by its sockets this draw (GL thread only). */
    private final com.openmason.engine.rig.JointPalette hostPose = new com.openmason.engine.rig.JointPalette();

    EntityAttachmentRenderer(SbeEntityRenderer sbeEntityRenderer, SocketItemQueue socketItems) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.socketItems = socketItems;
    }

    /**
     * Draws every model attached to {@code entityKey}'s sockets
     * ({@link com.stonebreak.mobs.sbe.EntityAttachments}), posed at the socket's
     * world frame for the host's current animation state — so attachments track
     * walk/graze/head-turn exactly. Sockets that no longer resolve to a host
     * part are skipped (never drawn at the model origin).
     */
    void render(Object entityKey,
                com.openmason.engine.entity.model.EntityAsset hostAsset,
                String variantName, com.stonebreak.mobs.sbe.AnimState anim,
                org.joml.Vector3dc position, float yawDegrees, Vector3f scale,
                float headYawDeg, float headPitchDeg,
                Matrix4f viewMatrix, Matrix4f projectionMatrix,
                com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        java.util.List<com.stonebreak.mobs.sbe.EntityAttachments.Attached> attached =
                com.stonebreak.mobs.sbe.EntityAttachments.get(entityKey);
        if (attached.isEmpty() || hostAsset == null) return;

        com.openmason.engine.entity.model.EntityGeometry geometry = hostAsset.geometryFor(variantName);
        if (geometry == null) return;
        Matrix4f base = SbePoseSolver.baseMatrix(position, yawDegrees, scale);
        // Pose the host once for all of its sockets (the attached models' own draws pose again,
        // so the palette is this renderer's, never the entity renderer's).
        SbePoseSolver.pose(geometry, hostAsset, anim, base, headYawDeg, headPitchDeg, null, hostPose);
        Matrix4f socket = new Matrix4f();
        for (com.stonebreak.mobs.sbe.EntityAttachments.Attached a : attached) {
            if (SbePoseSolver.socketWorldMatrix(geometry, hostPose, base, a.socketName(), socket) == null) {
                continue;
            }
            switch (a.visual()) {
                case AttachmentVisual.Model model -> renderModel(model, socket,
                        viewMatrix, projectionMatrix, world, cameraPos);
                // Items draw with the world shader in the drop pass; see SocketItemQueue.
                case AttachmentVisual.Item item -> socketItems.add(item.type(), item.state(), socket);
            }
        }
    }

    /**
     * {@link #render} for a host already posed in MODEL space by its animation graph (#457): every socket is read
     * from that palette and put under the host's {@code base} transform, so attachments follow the drawn pose.
     */
    void renderPosed(Object entityKey, com.openmason.engine.entity.model.EntityAsset hostAsset, String variantName,
                     com.openmason.engine.rig.JointPalette posed, Matrix4f base,
                     Matrix4f viewMatrix, Matrix4f projectionMatrix,
                     com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        java.util.List<com.stonebreak.mobs.sbe.EntityAttachments.Attached> attached =
                com.stonebreak.mobs.sbe.EntityAttachments.get(entityKey);
        if (attached.isEmpty() || hostAsset == null || posed == null) return;
        com.openmason.engine.entity.model.EntityGeometry geometry = hostAsset.geometryFor(variantName);
        if (geometry == null) return;
        Matrix4f modelSpace = new Matrix4f();
        Matrix4f socket = new Matrix4f();
        for (com.stonebreak.mobs.sbe.EntityAttachments.Attached a : attached) {
            if (SbePoseSolver.socketWorldMatrix(geometry, posed, MODEL_ORIGIN, a.socketName(), modelSpace) == null) {
                continue;
            }
            base.mul(modelSpace, socket);
            switch (a.visual()) {
                case AttachmentVisual.Model model -> renderModel(model, socket,
                        viewMatrix, projectionMatrix, world, cameraPos);
                case AttachmentVisual.Item item -> socketItems.add(item.type(), item.state(), socket);
            }
        }
    }

    /** The model-space base of a palette posed without one. */
    private static final Matrix4f MODEL_ORIGIN = new Matrix4f();

    private void renderModel(AttachmentVisual.Model model, Matrix4f socket,
                             Matrix4f viewMatrix, Matrix4f projectionMatrix,
                             com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        if (SbeRenderSupport.isTextured(model.asset())) {
            sbeEntityRenderer.render(model.asset(), model.variant(), null, socket,
                    viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f);
        } else {
            sbeEntityRenderer.renderColored(model.asset(), model.variant(), null, socket,
                    viewMatrix, projectionMatrix, ATTACHMENT_FALLBACK_COLOR, 0f, 0f);
        }
    }
}
