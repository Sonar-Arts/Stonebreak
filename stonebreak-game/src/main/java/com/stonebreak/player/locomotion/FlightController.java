package com.stonebreak.player.locomotion;

import com.stonebreak.core.Game;
import com.stonebreak.player.state.PhysicsState;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.DOUBLE_TAP_WINDOW;
import static com.stonebreak.player.PlayerConstants.FLY_VERTICAL_SPEED;
import static com.stonebreak.player.PlayerConstants.MOVE_SPEED;

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
            } else {
                shedFlightMomentum();
            }
            lastSpaceKeyTime = 0.0f;
            return true;
        }
        lastSpaceKeyTime = currentTime;
        return false;
    }

    /**
     * Shed flight momentum on any flight stop: a cancelled flight must not keep carrying
     * FLY_SPEED / FLY_VERTICAL_SPEED into normal physics, or the player plummets at descent
     * speed and lands at lethal velocity. Vertical momentum is dropped; horizontal is clamped
     * to ground-locomotion speed (direction preserved).
     */
    private void shedFlightMomentum() {
        Vector3f velocity = state.getVelocity();
        velocity.y = 0;
        float horizontal = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal > MOVE_SPEED) {
            float scale = MOVE_SPEED / horizontal;
            velocity.x *= scale;
            velocity.z *= scale;
        }
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
        boolean wasFlying = this.flying;
        this.flightEnabled = enabled;
        if (!enabled) {
            this.flying = false;
            // /fly off (or equivalent) mid-flight stops flight just like the double-tap
            // toggle — shed momentum too. Only when flight was actually active, so
            // disabling a capability the player wasn't using never touches their velocity.
            if (wasFlying) shedFlightMomentum();
        }
    }

    public void reset() {
        flying = false;
        lastSpaceKeyTime = 0.0f;
    }
}
