package com.stonebreak.mobs.sbe;

/**
 * One phase for the player's walk/run pose and footsteps. Both V2 clips plant
 * alternating feet at phases 0 and 0.5. Changing pace preserves the phase;
 * leaving locomotion resets it so starting/landing plants the first foot.
 */
public final class PlayerGaitClock {
    private double phase;
    private float duration;
    private boolean active;

    /** Advance the cycle, returning at most one audible contact per update (no hitch catch-up bursts). */
    public boolean update(float dt, float cycleDuration) {
        if (!Float.isFinite(cycleDuration) || cycleDuration <= 0f) {
            phase = 0;
            duration = 0;
            active = false;
            return false;
        }
        duration = cycleDuration;
        if (!Float.isFinite(dt) || dt <= 0f) return false;
        double next = phase + (double) dt / duration;
        boolean contact = !active || Math.floor(next * 2 + 1e-7) > Math.floor(phase * 2 + 1e-7);
        phase = next - Math.floor(next);
        active = true;
        return contact;
    }

    public boolean isActive() { return active; }

    /** The very same phase that produced this tick's contact is sent to the renderer. */
    public float timeSeconds() { return (float) (phase * duration); }
}
