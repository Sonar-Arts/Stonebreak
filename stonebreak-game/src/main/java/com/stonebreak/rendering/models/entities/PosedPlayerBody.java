package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.player.Player;
import com.stonebreak.player.animation.PlayerBodyAnimator;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * What a player-shaped body draws with in EVERY pass (#457): its graph palette (model space, posed once per update)
 * and its render-space base matrix. The figure ({@link PlayerFigureRenderer}), the sun/torch shadow casters
 * ({@link EntityShadowCasterRenderer}) and the debug wireframe all take it from here, so the shadow is the drawn
 * pose - head look and the attack layer included (the old shadow pass posed the base clip alone).
 */
record PosedPlayerBody(EntityAsset asset, JointPalette palette, Matrix4f base) {

    private static final Vector3f UNIT_SCALE = new Vector3f(1f, 1f, 1f);

    /** The local player's body, or null before its first tick. */
    static PosedPlayerBody of(Player player) {
        return player == null ? null
                : of(player.getBodyAnimator(), player.getPosition(), player.getBodyYaw(), UNIT_SCALE);
    }

    /** A remote player's or decoy's body, or null until it has been posed. */
    static PosedPlayerBody of(RemotePlayer rp) {
        return rp == null ? null : of(rp.bodyAnimator(), rp.getPosition(), rp.getBodyYaw(), rp.getScale());
    }

    static PosedPlayerBody of(PlayerBodyAnimator body, Vector3dc position, float yawDegrees, Vector3f scale) {
        if (body == null || !body.posed()) return null;
        return new PosedPlayerBody(body.set().asset(), body.palette(),
                SbePoseSolver.baseMatrix(position, yawDegrees, scale));
    }
}
