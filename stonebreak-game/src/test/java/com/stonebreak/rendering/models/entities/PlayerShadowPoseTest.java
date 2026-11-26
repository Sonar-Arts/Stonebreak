package com.stonebreak.rendering.models.entities;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.player.animation.PlayerBodyAnimator;
import com.stonebreak.player.animation.PlayerPoseInputs;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #457: the shadow pose equals the drawn pose. Every pass of a player-shaped body (figure, sun/torch shadow casters,
 * wireframe) draws {@link PosedPlayerBody}; here: it is the body's own graph palette under its base, head look and
 * the attack layer included - which the old shadow pass left out (it posed the base clip alone).
 */
class PlayerShadowPoseTest {

    private static final float DT = 1f / 60f;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    @Test
    void everyPassDrawsTheBodysOwnPalette() {
        RemotePlayer rp = new RemotePlayer(null, new Vector3d(3, 64, -2), 4, "remote");
        assertNull(PosedPlayerBody.of(rp), "nothing to draw before the first pose (the cylinder stands in)");
        rp.applyNetworkState(3, 64, -2, 40f, -20f);  // looking down and aside
        rp.setStateFlags((short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.ATTACKING));
        for (int i = 0; i < 20; i++) rp.updateClientVisuals(DT);

        PosedPlayerBody figure = PosedPlayerBody.of(rp);
        PosedPlayerBody shadow = PosedPlayerBody.of(rp);
        assertNotNull(figure);
        assertSame(rp.bodyAnimator().palette(), figure.palette());
        assertSame(figure.palette(), shadow.palette(), "the shadow draws the drawn palette");
        assertEquals(figure.base(), shadow.base());
        assertEquals(SbePoseSolver.baseMatrix(rp.getPosition(), rp.getBodyYaw(), rp.getScale()), figure.base());

        // That palette carries the head look and the punch: a body posed without them differs.
        try (PlayerBodyAnimator plain = PlayerBodyAnimator.player()) {
            PlayerPoseInputs in = new PlayerPoseInputs().set((short) PlayerStateFlags.ON_GROUND, 0f, false, 0f, 0f);
            for (int i = 0; i < 20; i++) plain.update(DT, in, PlayerBodyAnimator.Events.NONE);
            assertTrue(differs(plain.palette(), figure.palette()), "head look + attack layer are in the drawn pose");
        }
        rp.onRemovedFromWorld();
    }

    private static boolean differs(JointPalette a, JointPalette b) {
        Matrix4f ma = new Matrix4f();
        Matrix4f mb = new Matrix4f();
        for (int j = 0; j < a.count(); j++) {
            if (!a.skinMatrix(j, ma).equals(b.skinMatrix(j, mb), 1e-4f)) return true;
        }
        return false;
    }
}
