package com.stonebreak.player.locomotion;

import com.stonebreak.core.Game;
import com.stonebreak.player.state.PhysicsState;

import static com.stonebreak.player.PlayerConstants.JUMP_FORCE;
import static com.stonebreak.player.PlayerConstants.NORMAL_JUMP_GRACE_PERIOD;
import static com.stonebreak.player.PlayerConstants.WATER_BUOYANCY;
import static com.stonebreak.player.PlayerConstants.WATER_JUMP_BOOST;

/**
 * Handles jump input: initial-press detection, grace-period tracking for anti-float
 * logic, ground jumps, water-entry boost, held-key swimming buoyancy, and jump-release
 * damping for water exits. Works in concert with {@link FlightController} (which gets
 * first-refusal on the press for flight toggling).
 */
public class JumpHandler {

    private final PhysicsState state;
    private boolean wasJumpPressed;
    private float lastNormalJumpTime;

    public JumpHandler(PhysicsState state) {
        this.state = state;
    }

    /**
     * Process jump input for this frame. Called from movement processing after flight
     * toggle logic has had a chance to consume the press.
     *
     * @param jump whether jump key is currently held
     * @param flightConsumedToggle whether flight toggle fired this frame (skip normal jump)
     * @param flying whether player is currently flying
     */
    public void processJumpInput(boolean jump, boolean flightConsumedToggle, boolean flying) {
        boolean jumpPressed = jump && !wasJumpPressed;

        if (jumpPressed && !flying && state.isOnGround() && !state.isPhysicallyInWater()) {
            state.getVelocity().y = JUMP_FORCE;
            state.setOnGround(false);
            lastNormalJumpTime = Game.getInstance().getTotalTimeElapsed();
        }

        if (jumpPressed && !flying && state.isPhysicallyInWater()) {
            state.getVelocity().y += WATER_JUMP_BOOST;
        }

        if (jump && !flying && state.isPhysicallyInWater()) {
            state.getVelocity().y += WATER_BUOYANCY * Game.getDeltaTime();
        }

        // Release-damping for floating out of water
        boolean jumpReleased = !jump && wasJumpPressed;
        if (jumpReleased && !flying && !state.isOnGround() && state.getVelocity().y > 0 &&
                (state.isPhysicallyInWater() || state.wasInWaterLastFrame())) {
            state.getVelocity().y *= 0.3f;
        }

        wasJumpPressed = jump;
    }

    public float getLastNormalJumpTime() { return lastNormalJumpTime; }
    public boolean wasJumpPressed() { return wasJumpPressed; }
    public float getNormalJumpGracePeriod() { return NORMAL_JUMP_GRACE_PERIOD; }

    public void reset() {
        wasJumpPressed = false;
        lastNormalJumpTime = 0.0f;
    }
}
