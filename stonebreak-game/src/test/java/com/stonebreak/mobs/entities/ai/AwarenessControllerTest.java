package com.stonebreak.mobs.entities.ai;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Stealth awareness takes its target from a {@link PlayerLocator} (#340) — on the server, the
 * connected roster — instead of the client's {@code Game.getPlayer()} and that player's raycast.
 */
class AwarenessControllerTest {

    private final StubMob mob = new StubMob(EntityType.COW, new Vector3f(0, 64, 0));

    /** One player, wherever and however fast the test says. */
    private static final class FakePlayers implements PlayerLocator {
        Vector3f position;
        boolean sprinting;

        @Override
        public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
            return position == null ? null : out.set(position);
        }

        @Override
        public boolean nearestPlayerSprinting(Vector3f from) {
            return sprinting;
        }
    }

    private final FakePlayers players = new FakePlayers();

    private Vector3f behindTheMob(float distance) {
        Vector3f forward = new Vector3f(mob.getForwardDirection());
        return new Vector3f(mob.getPosition()).sub(forward.mul(distance));
    }

    private void run(AwarenessController awareness, float seconds) {
        for (float t = 0; t < seconds; t += 0.05f) {
            awareness.update(0.05f);
        }
    }

    @Test
    void nobodyAroundMeansNothingToNotice() {
        AwarenessController awareness = new AwarenessController(mob, players);

        run(awareness, 3f);

        assertEquals(AwarenessController.AwarenessState.UNAWARE, awareness.getState());
    }

    @Test
    void aSprintingPlayerBehindTheMobIsHeard() {
        AwarenessController awareness = new AwarenessController(mob, players);
        players.position = behindTheMob(6f); // out of the sight cone, inside the run noise radius

        run(awareness, 3f);
        assertEquals(AwarenessController.AwarenessState.UNAWARE, awareness.getState(),
                "walking: the server cannot hear it, and the mob is looking the other way");

        players.sprinting = true;
        run(awareness, 3f);
        assertNotEquals(AwarenessController.AwarenessState.UNAWARE, awareness.getState(),
                "a reported sprint is loud enough to notice");
        assertEquals(players.position.x,
                awareness.lastKnownPlayerPosition(new Vector3f()).x, 1e-4f);
    }
}
