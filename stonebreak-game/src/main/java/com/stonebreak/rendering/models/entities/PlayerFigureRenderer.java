package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Every player-shaped figure through the shared {@code SB_Player.sbe} model:
 * the local player in third person, remote players, illusion decoys and the UI
 * preview poses. Builds the {@link PlayerFigureRenderState} (base locomotion clip
 * + attack overlay envelope), picks the textured or flat-coloured SBE path, and
 * draws socket attachments afterwards. Falls back to the cylinder when the
 * asset is missing so a remote figure never goes invisible.
 */
final class PlayerFigureRenderer {
    private final SbeEntityRenderer sbeEntityRenderer;
    private final RemotePlayerRenderer remotePlayerRenderer;
    private final EntityAttachmentRenderer attachmentRenderer;

    // Stable-per-session random colour for the local player's untextured body model
    // (see renderLocalPlayer). Lazily initialised on first third-person render.
    private Vector4f localPlayerColor;

    PlayerFigureRenderer(SbeEntityRenderer sbeEntityRenderer,
                         RemotePlayerRenderer remotePlayerRenderer,
                         EntityAttachmentRenderer attachmentRenderer) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.remotePlayerRenderer = remotePlayerRenderer;
        this.attachmentRenderer = attachmentRenderer;
    }

    /**
     * Renders the local player's full body model in third-person view.
     *
     * <p>The local {@link com.stonebreak.player.Player} is not an
     * {@link com.stonebreak.mobs.entities.Entity} so it cannot go through
     * {@link EntityRenderer#renderEntity}; this dedicated method drives the SBE
     * pipeline directly with the player's state and animation clock.
     */
    void renderLocalPlayer(com.stonebreak.player.Player player,
                           Matrix4f viewMatrix, Matrix4f projectionMatrix,
                           com.stonebreak.world.World world, Vector3f cameraPos) {
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = SbeRenderSupport.playerAsset();
        if (asset == null) return;

        // The BASE clip is pure locomotion (jump one-shots use event-relative
        // time; looping walk uses the continuous clock). Attacking plays as an
        // OVERLAY on top: it owns only the parts its clip masks (authored in
        // the .omanim layer metadata), so the legs keep walking mid-swing. The
        // overlay envelope handles fade-in and pop-free early-exit fade-out.
        // Body facing and head angles are owned by PlayerBodyOrientation.
        String overlayState = null;
        float overlayTime = 0f;
        float overlayWeight = 0f;
        com.stonebreak.mobs.sbe.OverlayAnimState attackOverlay = player.getAttackOverlay();
        if (attackOverlay.isVisible()) {
            overlayState = com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(
                    com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.ATTACKING);
            com.openmason.engine.format.oma.ParsedAnimClip attackClip = asset.clipFor(overlayState);
            if (attackClip != null) {
                overlayTime = attackOverlay.time();
                overlayWeight = attackOverlay.weight(
                        attackClip.layer().fadeInSeconds(), attackClip.layer().fadeOutSeconds());
            }
        }

        PlayerFigureRenderState figure = new PlayerFigureRenderState(
                player.getPosition(),
                player.getBodyYaw(),
                new Vector3f(1f, 1f, 1f),
                player.getThirdPersonHeadYaw(),
                player.getThirdPersonHeadPitch(),
                com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(player.getBaseMovementState()),
                player.getBodyEventTime(),
                overlayState,
                overlayTime,
                overlayWeight,
                ensureLocalPlayerColor());

        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, world, cameraPos,
                com.stonebreak.mobs.sbe.EntityAttachments.LOCAL_PLAYER);
    }

    /**
     * Renders a {@link com.stonebreak.mobs.entities.RemotePlayer} (or its
     * {@code IllusionDecoy} subclass) through the shared player SBE model. The
     * model is always resolved from {@link EntityType#REMOTE_PLAYER}'s asset id,
     * so any texture/geometry change to {@code SB_Player.sbe} applies to every
     * player-shaped figure — remote players and decoys alike — without copying
     * anything per entity. Falls back to the cylinder if the asset is missing so
     * the figure never goes invisible.
     */
    void renderPlayerModel(com.stonebreak.mobs.entities.RemotePlayer rp,
                           float yaw, float headYaw, float headPitch,
                           Vector4f untexturedTint,
                           Matrix4f viewMatrix, Matrix4f projectionMatrix,
                           com.stonebreak.world.World world, Vector3f cameraPos) {
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = SbeRenderSupport.playerAsset();
        if (asset == null) {
            // Asset not loaded — fall back to cylinder so the figure never goes invisible.
            remotePlayerRenderer.render(rp, viewMatrix, projectionMatrix);
            return;
        }
        // Attack overlay from the replicated ATTACKING flag — same envelope + clip-fade
        // computation as the local player's third-person path (renderLocalPlayer), so
        // remote swings render identically to your own.
        String overlayState = null;
        float overlayTime = 0f;
        float overlayWeight = 0f;
        com.stonebreak.mobs.sbe.OverlayAnimState attackOverlay = rp.getAttackOverlay();
        if (attackOverlay.isVisible()) {
            overlayState = com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(
                    com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.ATTACKING);
            com.openmason.engine.format.oma.ParsedAnimClip attackClip = asset.clipFor(overlayState);
            if (attackClip != null) {
                overlayTime = attackOverlay.time();
                overlayWeight = attackOverlay.weight(
                        attackClip.layer().fadeInSeconds(), attackClip.layer().fadeOutSeconds());
            } else {
                overlayState = null;
            }
        }

        PlayerFigureRenderState figure = new PlayerFigureRenderState(
                rp.getPosition(),
                yaw,
                rp.getScale(),
                headYaw,
                headPitch,
                com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(rp.getMovementState()),
                rp.getAnimationController().getTotalAnimationTime(),
                overlayState,
                overlayTime,
                overlayWeight,
                untexturedTint);

        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, world, cameraPos, rp);
    }

    /**
     * Preview pose of the player model for UI (character creation): head faces
     * forward, no fog; {@code attachmentKey} null skips socket attachments.
     */
    void renderPlayerPreview(String stateName, float animationTime,
                             Vector3f position, float yawDegrees, Vector3f scale,
                             Matrix4f viewMatrix, Matrix4f projectionMatrix,
                             Object attachmentKey) {
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = SbeRenderSupport.playerAsset();
        if (asset == null) return;
        PlayerFigureRenderState figure = new PlayerFigureRenderState(
                position, yawDegrees, scale, 0f, 0f, stateName, animationTime,
                ensureLocalPlayerColor());
        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, null, null,
                attachmentKey);
    }

    /**
     * The single path that maps a {@link PlayerFigureRenderState} onto the SBE
     * pipeline — shared by the local player, remote players, and decoys. Textured
     * assets render normally; untextured assets fall back to {@code figure.tint()}
     * via the colored path (the textured path would skip every face and render
     * nothing). Models attached to {@code attachmentKey}'s sockets
     * ({@link com.stonebreak.mobs.sbe.EntityAttachments}) render after the
     * figure; null skips attachments (UI previews).
     */
    private void renderPlayerFigure(com.stonebreak.mobs.sbe.SbeEntityAsset asset,
                                    PlayerFigureRenderState figure,
                                    Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                    com.stonebreak.world.World world, Vector3f cameraPos,
                                    Object attachmentKey) {
        com.stonebreak.mobs.sbe.AnimState anim = figure.hasOverlay()
                ? new com.stonebreak.mobs.sbe.AnimState(figure.stateName(), figure.animTime(),
                        java.util.List.of(new com.stonebreak.mobs.sbe.AnimState.Overlay(
                                figure.overlayState(), figure.overlayTime(), figure.overlayWeight())))
                : com.stonebreak.mobs.sbe.AnimState.single(figure.stateName(), figure.animTime());

        if (SbeRenderSupport.isTextured(asset)) {
            sbeEntityRenderer.render(
                    asset, com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    anim, figure.position(), figure.yaw(), figure.scale(),
                    viewMatrix, projectionMatrix, world, cameraPos, figure.headYaw(), figure.headPitch());
        } else {
            sbeEntityRenderer.renderColored(
                    asset, com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    anim, figure.position(), figure.yaw(), figure.scale(),
                    viewMatrix, projectionMatrix, figure.tint(), figure.headYaw(), figure.headPitch());
        }

        if (attachmentKey != null) {
            attachmentRenderer.render(attachmentKey, asset,
                    com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT, anim,
                    figure.position(), figure.yaw(), figure.scale(),
                    figure.headYaw(), figure.headPitch(),
                    viewMatrix, projectionMatrix, world, cameraPos);
        }
    }

    /**
     * Stable-per-session colour for the local player's untextured body model.
     * Decoys reuse this so an untextured decoy matches its caster. Lazily
     * initialised on first use.
     */
    Vector4f ensureLocalPlayerColor() {
        if (localPlayerColor == null) {
            localPlayerColor = new Vector4f(
                    RemotePlayerRenderer.colorFor(new java.util.Random().nextInt()), 1f);
        }
        return localPlayerColor;
    }
}
