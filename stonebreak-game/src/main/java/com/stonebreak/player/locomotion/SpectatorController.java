package com.stonebreak.player.locomotion;

import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.state.PhysicsState;

/**
 * Owns spectator-mode state. Toggling spectator on forces flight enabled+flying and
 * remembers the prior flight state so it can be restored on toggle-off. Other
 * subsystems (collision, ground check, fall damage, jump-toggle) query
 * {@link #isActive()} to bypass their normal behavior.
 */
public class SpectatorController {

    private final PhysicsState state;
    private final FlightController flight;
    private final HealthController health;

    private boolean active;
    private boolean savedFlightEnabled;
    private boolean savedFlying;

    public SpectatorController(PhysicsState state, FlightController flight, HealthController health) {
        this.state = state;
        this.flight = flight;
        this.health = health;
    }

    public void setActive(boolean enable) {
        if (enable == active) return;

        if (enable) {
            savedFlightEnabled = flight.isFlightEnabled();
            savedFlying = flight.isFlying();
            flight.setFlightEnabled(true);
            flight.setFlying(true);
            active = true;
        } else {
            active = false;
            flight.setFlightEnabled(savedFlightEnabled);
            flight.setFlying(savedFlying);
            // Suppress fall damage briefly while the player re-enters normal physics.
            health.enableSpawnProtection();
        }

        state.setWasFalling(false);
        state.setPreviousY(state.getPosition().y);
    }

    public boolean isActive() { return active; }
}
