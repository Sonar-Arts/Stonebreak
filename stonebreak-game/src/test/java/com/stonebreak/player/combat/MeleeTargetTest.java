package com.stonebreak.player.combat;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * #374: the melee raycast and the decoy-killer lookup only consider entities that can take a
 * player's damage. Remote players (and decoys, a RemotePlayer subclass) cannot until PvP exists
 * (#343), so a swing must not resolve on them and pay out Rage, lifesteal, Quarry or stealth.
 */
class MeleeTargetTest {

    @Test
    void mobsAreMeleeTargets() {
        assertTrue(Player.isCombatTarget(new StubMob(EntityType.COW)));
    }

    @Test
    void remotePlayersAreNotMeleeTargets() {
        RemotePlayer other = new RemotePlayer(mock(World.class), new Vector3f(0f, 64f, 0f), 2, "bob");
        assertFalse(Player.isCombatTarget(other));
    }

    @Test
    void deadOrMissingTargetsAreNotMeleeTargets() {
        StubMob dead = new StubMob(EntityType.COW);
        dead.setAlive(false);
        assertFalse(Player.isCombatTarget(dead));
        assertFalse(Player.isCombatTarget(null));
    }
}
