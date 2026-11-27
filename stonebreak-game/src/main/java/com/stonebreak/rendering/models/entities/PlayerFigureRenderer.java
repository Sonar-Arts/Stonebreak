package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.BodyMounts;
import com.stonebreak.player.animation.PlayerBodyAnimator;
import com.stonebreak.player.animation.PlayerPoseInputs;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Every player-shaped figure through the shared {@code SB_Player.sbe} model: the local player in third person,
 * remote players, illusion decoys and the UI preview. Each body is posed by its own animation graph instance
 * ({@link PlayerBodyAnimator}, #457) once per update; this class only draws that palette under the body's base
 * matrix - textured, or flat-coloured for an untextured asset - and its socket attachments. The shadow passes draw
 * the very same palette ({@link EntityShadowCasterRenderer}). Remote figures fall back to the cylinder until their
 * body has been posed once.
 */
final class PlayerFigureRenderer {
    /** A preview frame never advances the preview body by more than this (a hidden preview resumes calmly). */
    private static final float MAX_PREVIEW_STEP = 0.1f;

    private final SbeEntityRenderer sbeEntityRenderer;
    private final RemotePlayerRenderer remotePlayerRenderer;
    private final EntityAttachmentRenderer attachmentRenderer;

    // Stable-per-session random colour for the local player's untextured body model.
    private Vector4f localPlayerColor;

    /** The UI preview's own body (standing; lazily created, lives with the renderer). */
    private PlayerBodyAnimator previewBody;
    private final PlayerPoseInputs previewInputs = new PlayerPoseInputs().standing();
    private float previewTime = Float.NaN;

    PlayerFigureRenderer(SbeEntityRenderer sbeEntityRenderer,
                         RemotePlayerRenderer remotePlayerRenderer,
                         EntityAttachmentRenderer attachmentRenderer) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.remotePlayerRenderer = remotePlayerRenderer;
        this.attachmentRenderer = attachmentRenderer;
    }

    /** The local player's body in third person (and the main menu's preview of the last world). */
    void renderLocalPlayer(com.stonebreak.player.Player player,
                           Matrix4f viewMatrix, Matrix4f projectionMatrix,
                           com.stonebreak.world.World world, Vector3dc cameraPos) {
        PosedPlayerBody body = PosedPlayerBody.of(player);
        if (body == null) return;
        draw(body, ensureLocalPlayerColor(), viewMatrix, projectionMatrix, world, cameraPos, player.mounts());
    }

    /**
     * A {@link com.stonebreak.mobs.entities.RemotePlayer} or its {@code IllusionDecoy} subclass, posed by its own
     * body ({@link com.stonebreak.mobs.entities.RemotePlayer#bodyAnimator()}); the cylinder until that body exists.
     */
    void renderPlayerModel(com.stonebreak.mobs.entities.RemotePlayer rp, Vector4f untexturedTint,
                           Matrix4f viewMatrix, Matrix4f projectionMatrix,
                           com.stonebreak.world.World world, Vector3dc cameraPos) {
        PosedPlayerBody body = PosedPlayerBody.of(rp);
        if (body == null) {
            remotePlayerRenderer.render(rp, viewMatrix, projectionMatrix);
            return;
        }
        draw(body, untexturedTint, viewMatrix, projectionMatrix, world, cameraPos, rp.lookMounts());
    }

    /**
     * The UI preview (character creation, inventory): a standing body with the head forward, advanced by the preview
     * clock {@code time} (seconds); {@code mounts} null skips socket attachments.
     */
    void renderPlayerPreview(float time, Vector3f position, float yawDegrees, Vector3f scale,
                             Matrix4f viewMatrix, Matrix4f projectionMatrix, BodyMounts mounts) {
        if (previewBody == null) previewBody = PlayerBodyAnimator.player();
        float dt = Float.isNaN(previewTime) ? 0f : Math.clamp(time - previewTime, 0f, MAX_PREVIEW_STEP);
        previewTime = time;
        if (dt > 0f || !previewBody.posed()) previewBody.update(dt, previewInputs, PlayerBodyAnimator.Events.NONE);
        draw(new PosedPlayerBody(previewBody.set().asset(), previewBody.palette(),
                        SbePoseSolver.baseMatrix(position, yawDegrees, scale)), ensureLocalPlayerColor(),
                viewMatrix, projectionMatrix, null, null, mounts);
    }

    /**
     * The Focus battle's monk: a named clip at a caller time (its combat clips are not on the graph; the battle stage
     * is to be retired with the player rebuild, #457).
     */
    void renderStagedPose(String stateName, float animationTime, Vector3f position, float yawDegrees, Vector3f scale,
                          Matrix4f viewMatrix, Matrix4f projectionMatrix, BodyMounts mounts) {
        EntityAsset asset = SbeRenderSupport.playerAsset();
        if (asset == null) return;
        AnimState anim = AnimState.single(stateName, animationTime);
        Vector3d at = new Vector3d(position);
        if (SbeRenderSupport.isTextured(asset)) {
            sbeEntityRenderer.render(asset, EntityAsset.DEFAULT_VARIANT, anim, at, yawDegrees, scale,
                    viewMatrix, projectionMatrix, null, null, 0f, 0f);
        } else {
            sbeEntityRenderer.renderColored(asset, EntityAsset.DEFAULT_VARIANT, anim, at, yawDegrees, scale,
                    viewMatrix, projectionMatrix, ensureLocalPlayerColor(), 0f, 0f);
        }
        if (mounts != null) {
            attachmentRenderer.render(mounts, asset, EntityAsset.DEFAULT_VARIANT, anim, at, yawDegrees, scale,
                    0f, 0f, viewMatrix, projectionMatrix, null, null);
        }
    }

    /** The one way a posed player body is drawn: textured or tinted, then its sockets. */
    private void draw(PosedPlayerBody body, Vector4f tint,
                      Matrix4f viewMatrix, Matrix4f projectionMatrix,
                      com.stonebreak.world.World world, Vector3dc cameraPos, BodyMounts mounts) {
        EntityAsset asset = body.asset();
        JointPalette posed = body.palette();
        Matrix4f base = body.base();
        if (SbeRenderSupport.isTextured(asset)) {
            sbeEntityRenderer.renderPosed(asset, EntityAsset.DEFAULT_VARIANT, posed, base,
                    viewMatrix, projectionMatrix, world, cameraPos);
        } else {
            sbeEntityRenderer.renderColoredPosed(asset, EntityAsset.DEFAULT_VARIANT, posed, base,
                    viewMatrix, projectionMatrix, tint);
        }
        if (mounts != null) {
            attachmentRenderer.renderPosed(mounts, asset, EntityAsset.DEFAULT_VARIANT, posed, base,
                    viewMatrix, projectionMatrix, world, cameraPos);
        }
    }

    /**
     * Stable-per-session colour for the local player's untextured body model. Decoys reuse this so an untextured decoy
     * matches its caster. Lazily initialised on first use.
     */
    Vector4f ensureLocalPlayerColor() {
        if (localPlayerColor == null) {
            localPlayerColor = new Vector4f(
                    RemotePlayerRenderer.colorFor(new java.util.Random().nextInt()), 1f);
        }
        return localPlayerColor;
    }

    void cleanup() {
        if (previewBody != null) {
            previewBody.close();
            previewBody = null;
        }
    }
}
