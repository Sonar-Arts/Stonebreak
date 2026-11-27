package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.sbe.AttachmentVisual;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws what is mounted on a host body's sockets
 * ({@link com.stonebreak.mobs.sbe.BodyMounts}) — e.g. an equipped hat or a held weapon —
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
     * Draws every mount of {@code mounts} that has a visual (#459: resolved natively, {@code cr_mounts}), posed at
     * its socket's frame for the host's current animation state, so attachments track walk/graze/head-turn exactly.
     * Mounts whose socket the host lacks are skipped (never drawn at the model origin).
     */
    void render(com.stonebreak.mobs.sbe.BodyMounts mounts,
                com.openmason.engine.entity.model.EntityAsset hostAsset,
                String variantName, com.stonebreak.mobs.sbe.AnimState anim,
                org.joml.Vector3dc position, float yawDegrees, Vector3f scale,
                float headYawDeg, float headPitchDeg,
                Matrix4f viewMatrix, Matrix4f projectionMatrix,
                com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        if (mounts == null || !mounts.hasVisuals() || hostAsset == null) return;
        com.openmason.engine.entity.model.EntityGeometry geometry = hostAsset.geometryFor(variantName);
        com.openmason.engine.rig.SocketSet sockets = com.stonebreak.mobs.sbe.HostSockets.of(hostAsset, variantName);
        if (geometry == null || sockets == null) return;
        Matrix4f base = SbePoseSolver.baseMatrix(position, yawDegrees, scale);
        // Pose the host once for all of its mounts (the attached models' own draws pose again,
        // so the palette is this renderer's, never the entity renderer's). It is posed WITH the base.
        SbePoseSolver.pose(geometry, hostAsset, anim, base, headYawDeg, headPitchDeg, null, hostPose);
        draw(mounts, mounts.resolve(sockets, hostPose, null, base), viewMatrix, projectionMatrix, world, cameraPos);
    }

    /**
     * {@link #render} for a host already posed in MODEL space by its animation graph (#457): every mount is resolved
     * on that palette under the host's {@code base} transform, so attachments follow the drawn pose.
     */
    void renderPosed(com.stonebreak.mobs.sbe.BodyMounts mounts,
                     com.openmason.engine.entity.model.EntityAsset hostAsset, String variantName,
                     com.openmason.engine.rig.JointPalette posed, Matrix4f base,
                     Matrix4f viewMatrix, Matrix4f projectionMatrix,
                     com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        if (mounts == null || !mounts.hasVisuals() || hostAsset == null || posed == null) return;
        com.openmason.engine.rig.SocketSet sockets = com.stonebreak.mobs.sbe.HostSockets.of(hostAsset, variantName);
        if (sockets == null) return;
        draw(mounts, mounts.resolve(sockets, posed, base, base), viewMatrix, projectionMatrix, world, cameraPos);
    }

    private final Matrix4f socket = new Matrix4f();

    private void draw(com.stonebreak.mobs.sbe.BodyMounts mounts,
                      java.util.List<com.stonebreak.mobs.sbe.BodyMounts.Mount> resolved,
                      Matrix4f viewMatrix, Matrix4f projectionMatrix,
                      com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        for (int i = 0; i < resolved.size(); i++) {
            AttachmentVisual visual = resolved.get(i).visual();
            if (visual == null || mounts.frame(i, socket) == null) continue;
            switch (visual) {
                case AttachmentVisual.Model model -> renderModel(model, new Matrix4f(socket),
                        viewMatrix, projectionMatrix, world, cameraPos);
                // Items draw with the world shader in the drop pass; see SocketItemQueue.
                case AttachmentVisual.Item item -> socketItems.add(item.type(), item.state(), new Matrix4f(socket));
            }
        }
    }

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
