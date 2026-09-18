package com.stonebreak.battle;

/** One ATB gauge, 0..1. Knows nothing about who owns it or why its fill rate changes. */
final class AtbGauge {

    private final float fillSeconds;
    private float value;
    private boolean announced;

    AtbGauge(float fillSeconds, float initial) {
        this.fillSeconds = Math.max(1.0e-3f, fillSeconds);
        this.value = clamp01(initial);
    }

    float value() {
        return value;
    }

    /** Full AND announced: the turn only exists once TurnReady has been raised for it. */
    boolean full() {
        return announced;
    }

    /** Seconds until the next fill announcement at the given speed; infinite when announced or frozen. */
    float timeToFull(float speed) {
        if (announced || speed <= 0f) return Float.POSITIVE_INFINITY;
        return (1f - value) * fillSeconds / speed;
    }

    /**
     * Advances the gauge. Returns true only on the step that fills it, so the caller raises
     * TurnReady exactly once per fill.
     */
    boolean advance(float dt, float speed) {
        if (announced || speed <= 0f) return false;
        // Compared against the same expression the scheduler sliced by, so a slice that ends exactly
        // on the fill always lands it instead of stopping one rounding error short. A gauge that
        // starts full announces on its first step.
        if (dt >= timeToFull(speed)) {
            value = 1f;
            announced = true;
            return true;
        }
        value = Math.min(1f, value + dt * speed / fillSeconds);
        return false;
    }

    void reset(float to) {
        value = clamp01(to);
        announced = false;
    }

    private static float clamp01(float v) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 0f;
    }
}
