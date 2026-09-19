package com.stonebreak.mobs.entities;

import com.stonebreak.network.packet.player.PlayerStateFlags;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.*;
import static org.junit.jupiter.api.Assertions.*;

class RemotePlayerAnimationTest {
    @Test
    void remotePunchContinuesAfterReplicatedAttackFlagClears() {
        var player = new RemotePlayer(null, new Vector3f(), 1, "test");
        player.setStateFlags((byte) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.ATTACKING));
        player.updateClientVisuals(0.01f);
        player.setStateFlags((byte) PlayerStateFlags.ON_GROUND);
        player.updateClientVisuals(0.25f);
        player.updateClientVisuals(0.13f);
        assertTrue(player.getAttackOverlay().isVisible());
        assertEquals(0.38f, player.getAttackOverlay().time(), 1e-6f);
        assertEquals(1f, player.getAttackOverlay().weight(0.08f, 0.12f));
    }

    @Test
    void replicatedSprintFlagSelectsSprintAtHighFrameRatesAndKeepsAttackOverlay() {
        var player = new RemotePlayer(null, new Vector3f(), 1, "test");
        player.setStateFlags((byte) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.SPRINTING
                | PlayerStateFlags.ATTACKING));
        player.applyNetworkState(0.025f, 0, 0, 0, 0);
        player.updateClientVisuals(1f / 240f);
        assertEquals(SPRINTING, player.getMovementState());
        assertTrue(player.getBodyAnimationTime() > 0f);
        assertTrue(player.getAttackOverlay().isVisible());

        player.setStateFlags((byte) PlayerStateFlags.ON_GROUND);
        player.applyNetworkState(0.04f, 0, 0, 0, 0);
        player.updateClientVisuals(1f / 240f);
        assertEquals(WALKING, player.getMovementState());
        player.updateClientVisuals(1f / 240f);
        assertEquals(IDLE, player.getMovementState());
    }

    @Test
    void jumpOverridesLandSprintAndWaterRetainsSwimmingPlaceholder() {
        var player = new RemotePlayer(null, new Vector3f(), 1, "test");
        player.setStateFlags((byte) (PlayerStateFlags.SPRINTING | PlayerStateFlags.AIRBORNE));
        player.applyNetworkState(0.1f, 0, 0, 0, 0);
        player.updateClientVisuals(0.016f);
        assertEquals(JUMPING, player.getMovementState());
        player.setStateFlags((byte) (PlayerStateFlags.SPRINTING | PlayerStateFlags.SWIMMING
                | PlayerStateFlags.AIRBORNE));
        player.applyNetworkState(0.2f, 0, 0, 0, 0);
        player.updateClientVisuals(0.016f);
        assertEquals(WALKING, player.getMovementState());
    }
}
