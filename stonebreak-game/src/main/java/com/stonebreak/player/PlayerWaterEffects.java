package com.stonebreak.player;

import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.rendering.effects.WaterRippleParticles;
import com.stonebreak.rendering.effects.WaterSplashParticles;
import org.joml.Vector3f;

/**
 * Water entry splash and surface ripple particle effects that follow the player.
 * Owns the particle systems and the ripple spawn cadence; advanced once per tick
 * by {@link PlayerUpdatePipeline}.
 */
final class PlayerWaterEffects {

    private final WaterSplashParticles splashParticles = new WaterSplashParticles();
    private final WaterRippleParticles rippleParticles = new WaterRippleParticles();
    private float rippleSpawnTimer = 0f;

    /**
     * @param eyesSubmerged true when the player's eyes are underwater (swimming state);
     *                      ripples are a surface effect and are suppressed while submerged.
     */
    void update(float dt, PhysicsState state, boolean eyesSubmerged) {
        if (state.justEnteredWaterThisFrame()) {
            float impactSpeed = Math.max(0f, -state.getVelocity().y);
            splashParticles.burst(state.getPosition(), impactSpeed);
            rippleParticles.spawn(state.getPosition());
            rippleSpawnTimer = 0f;
        }
        splashParticles.update(dt);
        // Ripples are a surface effect — suppress them once the player's eyes are
        // submerged (fully underwater), not just "touching" water.
        if (state.isPhysicallyInWater() && !eyesSubmerged) {
            Vector3f vel = state.getVelocity();
            float horizSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            rippleSpawnTimer += dt;
            if (horizSpeed > PlayerConstants.WATER_RIPPLE_SPEED_THRESHOLD
                    && rippleSpawnTimer >= PlayerConstants.WATER_RIPPLE_SPAWN_INTERVAL) {
                rippleParticles.spawn(state.getPosition());
                rippleSpawnTimer = 0f;
            }
        } else {
            rippleSpawnTimer = 0f;
        }
        rippleParticles.update(dt);
    }

    WaterSplashParticles getSplashParticles() { return splashParticles; }
    WaterRippleParticles getRippleParticles() { return rippleParticles; }
}
