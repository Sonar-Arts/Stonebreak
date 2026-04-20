package com.stonebreak.player.combat;

import com.stonebreak.player.state.PhysicsState;

import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;

/**
 * Tracks the player's descent arc and applies damage on landing. No damage for falls
 * of 4 blocks or less; 1/4 heart per additional block. Respects spawn protection.
 */
public class FallDamageHandler {

    private final PhysicsState state;
    private final HealthController health;

    public FallDamageHandler(PhysicsState state, HealthController health) {
        this.state = state;
        this.health = health;
    }

    public void update(boolean flying) {
        if (health.hasSpawnProtection()) {
            state.setPreviousY(state.getPosition().y);
            state.setWasFalling(false);
            return;
        }

        if (!state.isOnGround() && state.getVelocity().y < 0 && !flying) {
            state.setWasFalling(true);
        }

        if (state.isOnGround() && state.wasFalling()) {
            float fallDistance = state.getPreviousY() - state.getPosition().y;
            if (fallDistance > 4.0f) {
                float damage = (fallDistance - 4.0f) * (HEALTH_PER_HEART * 0.25f);
                health.damage(damage);
            }
            state.setWasFalling(false);
        }

        if (state.isOnGround() || !state.wasFalling()) {
            state.setPreviousY(state.getPosition().y);
        }
    }
}
