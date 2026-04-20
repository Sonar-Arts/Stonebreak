package com.stonebreak.player.locomotion;

import com.stonebreak.core.Game;
import com.stonebreak.player.state.PhysicsState;

import static com.stonebreak.player.PlayerConstants.DOUBLE_TAP_WINDOW;
import static com.stonebreak.player.PlayerConstants.FLY_VERTICAL_SPEED;

/**
 * Owns the creative-style flight toggle: tracks whether flight is enabled (via command)
 * and whether the player is currently flying (toggled by double-tap jump). Also drives
 * explicit vertical ascent/descent when flying.
 */
public class FlightController {

    private final PhysicsState state;
    private boolean flightEnabled;
    private boolean flying;
    private float lastSpaceKeyTime;

    public FlightController(PhysicsState state) {
        this.state = state;
    }

    /** Call on each jump-key press to process double-tap toggle. Returns true if toggled. */
    public boolean handleJumpPressForToggle() {
        if (!flightEnabled) return false;
        float currentTime = Game.getInstance().getTotalTimeElapsed();
        if (currentTime - lastSpaceKeyTime <= DOUBLE_TAP_WINDOW) {
            flying = !flying;
            if (flying) {
                state.setOnGround(false);
                state.getVelocity().y = 0;
            }
            lastSpaceKeyTime = 0.0f;
            return true;
        }
        lastSpaceKeyTime = currentTime;
        return false;
    }

    public void processAscent(boolean shift) {
        if (!flying) return;
        float speed = shift ? FLY_VERTICAL_SPEED * 2.0f : FLY_VERTICAL_SPEED;
        state.getVelocity().y = speed;
    }

    public void processDescent(boolean shift) {
        if (!flying) return;
        float speed = shift ? FLY_VERTICAL_SPEED * 2.0f : FLY_VERTICAL_SPEED;
        state.getVelocity().y = -speed;
    }

    public boolean isFlying() { return flying; }
    public void setFlying(boolean flying) { this.flying = flying; }

    public boolean isFlightEnabled() { return flightEnabled; }

    public void setFlightEnabled(boolean enabled) {
        this.flightEnabled = enabled;
        if (!enabled) this.flying = false;
    }

    public void reset() {
        flying = false;
        lastSpaceKeyTime = 0.0f;
    }
}
