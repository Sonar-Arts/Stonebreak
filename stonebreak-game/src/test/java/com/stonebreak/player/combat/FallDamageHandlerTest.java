package com.stonebreak.player.combat;

import com.stonebreak.player.state.PhysicsState;
import org.junit.jupiter.api.Test;

import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;
import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for fall-damage tracking around the flight toggle. The reported bug:
 * fly, fall, toggle flight back on to catch yourself mid-air, then touch the ground —
 * the landing killed the player because {@code wasFalling} stayed set and
 * {@code previousY} stayed frozen at the pre-flight height.
 */
class FallDamageHandlerTest {

    /** Fresh HealthControllers start under spawn protection, which gates all fall damage. */
    private static HealthController unprotectedHealth() {
        HealthController health = new HealthController();
        health.updateSpawnProtection(SPAWN_PROTECTION_DURATION + 0.1f, true);
        return health;
    }

    @Test
    void catchingSelfMidAirVoidsTheStaleFall() {
        PhysicsState state = new PhysicsState();
        HealthController health = unprotectedHealth();
        FallDamageHandler handler = new FallDamageHandler(state, health);

        // Falling from y=140: the fall registers and previousY freezes at 140.
        state.getPosition().set(0, 140, 0);
        state.setPreviousY(140);
        state.getVelocity().set(0, -10, 0);
        handler.update(false);
        assertTrue(state.wasFalling());

        // The player double-taps to re-catch with flight at y=120, then descends
        // under flight control down to y=95 and touches the ground at y=94.5.
        state.getPosition().y = 120;
        state.getVelocity().set(0, 0, 0);
        handler.update(true);
        assertFalse(state.wasFalling());

        state.getPosition().y = 95;
        handler.update(true);

        state.getPosition().y = 94.5f;
        state.getVelocity().y = 0;
        state.setOnGround(true);
        handler.update(false);

        // A one-block drop must not carry the stale 45-block "fall" from the
        // pre-flight height.
        assertEquals(health.getMaxHealth(), health.getHealth(), 1e-4f);
    }

    @Test
    void longFallWithoutFlightStillDamagesOnLanding() {
        PhysicsState state = new PhysicsState();
        HealthController health = unprotectedHealth();
        FallDamageHandler handler = new FallDamageHandler(state, health);

        state.getPosition().set(0, 140, 0);
        state.setPreviousY(140);
        state.getVelocity().set(0, -10, 0);
        handler.update(false);
        assertTrue(state.wasFalling());

        state.getPosition().y = 100;
        state.getVelocity().y = 0;
        state.setOnGround(true);
        handler.update(false);

        float expected = (40f - 4f) * (HEALTH_PER_HEART * 0.25f);
        assertEquals(health.getMaxHealth() - expected, health.getHealth(), 1e-3f);
        assertFalse(state.wasFalling());
    }

    @Test
    void normalFlightDescentDoesNotRegisterAsAFall() {
        PhysicsState state = new PhysicsState();
        HealthController health = unprotectedHealth();
        FallDamageHandler handler = new FallDamageHandler(state, health);

        // Descending under flight control the whole way down: never a fall.
        state.getPosition().set(0, 140, 0);
        state.setPreviousY(140);
        state.getVelocity().set(0, -15, 0);
        handler.update(true);
        state.getPosition().y = 100;
        handler.update(true);

        state.getPosition().y = 99.5f;
        state.getVelocity().y = 0;
        state.setOnGround(true);
        handler.update(true);

        assertEquals(health.getMaxHealth(), health.getHealth(), 1e-4f);
    }
}
