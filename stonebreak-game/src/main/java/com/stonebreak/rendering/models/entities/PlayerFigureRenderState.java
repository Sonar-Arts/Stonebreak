package com.stonebreak.rendering.models.entities;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Immutable snapshot of everything needed to draw one player-shaped SBE figure.
 *
 * <p>The local player, remote multiplayer players, and illusion decoys all render
 * through the same {@code SB_Player.sbe} asset. Each source supplies its own values
 * here, and {@link EntityRenderer#renderPlayerFigure} maps a single carrier onto the
 * SBE pipeline — so there is exactly one place that knows how to draw a player figure.
 *
 * <p>{@code stateName} is the BASE (locomotion) clip. An optional overlay clip
 * (e.g. attacking) plays on top of it, taking over the parts its clip masks —
 * {@code overlayWeight <= 0} means no overlay (the common case for remote
 * players, whose actions are not replicated yet).
 *
 * @param position      world position of the model origin
 * @param yaw           lower-body facing in model space (degrees; {@code cameraYaw + 180})
 * @param scale         world scale
 * @param headYaw       head yaw relative to the body (degrees; 0 = no head turn)
 * @param headPitch     head pitch (degrees; 0 = level)
 * @param stateName     base SBE animation-state name (unknown/null → rest pose)
 * @param animTime      elapsed base clip time in seconds
 * @param overlayState  overlay SBE state name (null → none)
 * @param overlayTime   seconds since the overlay started (unwrapped)
 * @param overlayWeight caller envelope weight in {@code [0,1]}; {@code <= 0} → none
 * @param tint          colour used when the asset is untextured (colored fallback path)
 */
public record PlayerFigureRenderState(
        Vector3f position,
        float yaw,
        Vector3f scale,
        float headYaw,
        float headPitch,
        String stateName,
        float animTime,
        String overlayState,
        float overlayTime,
        float overlayWeight,
        Vector4f tint) {

    /** Overlay-free carrier — the pre-mixing shape (remote players, decoys). */
    public PlayerFigureRenderState(Vector3f position, float yaw, Vector3f scale,
                                   float headYaw, float headPitch,
                                   String stateName, float animTime, Vector4f tint) {
        this(position, yaw, scale, headYaw, headPitch, stateName, animTime,
                null, 0f, 0f, tint);
    }

    public boolean hasOverlay() {
        return overlayState != null && overlayWeight > 0f;
    }
}
