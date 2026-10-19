package com.stonebreak.player.locomotion;

import com.stonebreak.player.state.PhysicsState;
import org.junit.jupiter.api.Test;

import static com.stonebreak.player.PlayerConstants.MOVE_SPEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the flight toggle. At test time {@code Game.getTotalTimeElapsed()}
 * is 0 and {@code lastSpaceKeyTime} starts at 0, so every press lands inside the double-tap
 * window: the first call toggles flight on, the second toggles it back off.
 */
class FlightControllerTest {

    @Test
    void togglingFlightOffShedsFlightMomentum() {
        PhysicsState state = new PhysicsState();
        FlightController flight = new FlightController(state);
        flight.setFlightEnabled(true);

        // Full-tilt flight: FLY_SPEED horizontal (2.5 x MOVE_SPEED = 50) and descent-rate
        // vertical velocity, as when the player was holding Ctrl when they double-tapped.
        state.getVelocity().set(48f, -15f, 14f);

        assertTrue(flight.handleJumpPressForToggle());
        assertTrue(flight.isFlying());
        assertTrue(flight.handleJumpPressForToggle());
        assertFalse(flight.isFlying());

        // The cancelled flight must not carry flight velocities into normal physics:
        // vertical momentum is dropped and horizontal is clamped to ground-locomotion speed.
        assertEquals(0f, state.getVelocity().y, 1e-6f);
        float horizontal = (float) Math.sqrt(state.getVelocity().x * state.getVelocity().x
                + state.getVelocity().z * state.getVelocity().z);
        assertEquals(MOVE_SPEED, horizontal, 1e-3f);
        assertEquals(MOVE_SPEED / 50f * 48f, state.getVelocity().x, 1e-3f);
        assertEquals(MOVE_SPEED / 50f * 14f, state.getVelocity().z, 1e-3f);
    }

    @Test
    void togglingFlightOffWithModestMomentumLeavesItAlone() {
        PhysicsState state = new PhysicsState();
        FlightController flight = new FlightController(state);
        flight.setFlightEnabled(true);

        // Hovering nearly still when the toggle fires: nothing to shed.
        state.getVelocity().set(3f, 0f, -4f);

        assertTrue(flight.handleJumpPressForToggle());
        assertTrue(flight.handleJumpPressForToggle());
        assertFalse(flight.isFlying());

        assertEquals(0f, state.getVelocity().y, 1e-6f);
        assertEquals(5f, (float) Math.sqrt(state.getVelocity().x * state.getVelocity().x
                + state.getVelocity().z * state.getVelocity().z), 1e-4f);
    }

    @Test
    void disablingFlightCapabilityMidFlightShedsMomentum() {
        PhysicsState state = new PhysicsState();
        FlightController flight = new FlightController(state);
        flight.setFlightEnabled(true);

        // Flying full-tilt, then the /fly off command path stops flight.
        state.getVelocity().set(48f, -15f, 14f);
        assertTrue(flight.handleJumpPressForToggle());
        assertTrue(flight.isFlying());

        flight.setFlightEnabled(false);
        assertFalse(flight.isFlying());
        assertEquals(0f, state.getVelocity().y, 1e-6f);
        assertEquals(MOVE_SPEED, (float) Math.sqrt(state.getVelocity().x * state.getVelocity().x
                + state.getVelocity().z * state.getVelocity().z), 1e-3f);
    }

    @Test
    void disablingFlightCapabilityWhileNotFlyingLeavesVelocityAlone() {
        PhysicsState state = new PhysicsState();
        FlightController flight = new FlightController(state);

        // Walking (sprinting) player runs /fly off: capability flip only, no velocity edit.
        state.getVelocity().set(10f, 8.5f, 0f);
        flight.setFlightEnabled(false);

        assertFalse(flight.isFlying());
        assertEquals(10f, state.getVelocity().x, 1e-6f);
        assertEquals(8.5f, state.getVelocity().y, 1e-6f);
    }
}
