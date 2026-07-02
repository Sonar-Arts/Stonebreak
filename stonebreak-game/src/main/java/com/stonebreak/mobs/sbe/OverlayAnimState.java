package com.stonebreak.mobs.sbe;

/**
 * Caller-owned envelope tracker for one overlay animation slot (e.g. the
 * player's attack). Keeps the renderer stateless: the game updates this once
 * per tick with whether the overlay's driving state is active, and reads back
 * the clip-relative time and an envelope weight that handles both smooth
 * entry and <em>early exit</em> — an attack cancelled mid-swing fades out
 * from its current weight instead of popping.
 *
 * <p>Semantics:
 * <ul>
 *   <li>While active, {@code weight} ramps {@code elapsed / fadeIn → 1}.</li>
 *   <li>On deactivation the current weight is captured and ramps to 0 over
 *       {@code fadeOut}; {@link #time()} keeps advancing so the pose holds
 *       its clamped last frame while fading rather than rewinding.</li>
 *   <li>Re-activating during a fade-out restarts the clip from 0 (a new
 *       swing), ramping up from the residual weight so there is no pop.</li>
 * </ul>
 *
 * <p>The natural end-of-clip fade of a non-looping overlay is separate — the
 * renderer derives it from clip metadata; the effective weight is the product
 * of the two. Server-safe: no rendering imports.
 */
public final class OverlayAnimState {

    private boolean active;
    private float elapsed;        // seconds since the overlay started (keeps running during fade-out)
    private float exitElapsed;    // seconds since deactivation
    private float weightAtExit;   // envelope weight captured when deactivated
    private float lastWeight;     // last computed weight, for capture on exit

    /**
     * Advance the tracker.
     *
     * @param dt          seconds since last update
     * @param nowActive   whether the overlay's driving state (e.g. attacking)
     *                    is active this tick
     */
    public void update(float dt, boolean nowActive) {
        if (nowActive) {
            if (!active) {
                // (Re)entry: new clip run from t=0. The residual fade-out
                // weight becomes a floor so re-triggering mid-fade can't pop.
                elapsed = 0f;
                weightAtExit = lastWeight;
            } else {
                elapsed += dt;
            }
            active = true;
            exitElapsed = 0f;
        } else {
            if (active) {
                // Capture the envelope where it was for a pop-free fade-out.
                weightAtExit = lastWeight;
                exitElapsed = 0f;
            } else {
                exitElapsed += dt;
            }
            active = false;
            elapsed += dt;   // pose freezes at the clip's clamped last frame while fading
        }
    }

    /** Whether the overlay still contributes (active, or fading out). */
    public boolean isVisible() {
        return active || lastWeight > 0f;
    }

    /** Seconds since the overlay clip started; keeps advancing during fade-out. */
    public float time() {
        return elapsed;
    }

    /**
     * Envelope weight in {@code [0,1]} for the given fade durations
     * (typically the clip's authored {@code fadeInSeconds}/{@code fadeOutSeconds}).
     * Also records the result so a subsequent deactivation fades from it.
     */
    public float weight(float fadeIn, float fadeOut) {
        float w;
        if (active) {
            float in = fadeIn <= 0f ? 1f : Math.min(elapsed / fadeIn, 1f);
            // Continue up from a residual fade-out weight instead of snapping to the ramp.
            w = Math.max(in, Math.min(weightAtExit, 1f));
        } else {
            if (weightAtExit <= 0f) {
                w = 0f;
            } else if (fadeOut <= 0f) {
                w = 0f;
            } else {
                w = weightAtExit * (1f - Math.min(exitElapsed / fadeOut, 1f));
            }
        }
        w = Math.min(Math.max(w, 0f), 1f);
        lastWeight = w;
        return w;
    }

    /** Reset to fully inactive (e.g. on respawn/model change). */
    public void reset() {
        active = false;
        elapsed = 0f;
        exitElapsed = 0f;
        weightAtExit = 0f;
        lastWeight = 0f;
    }
}
