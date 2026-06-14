package com.openmason.main.systems.mortar.anim;

/**
 * A single scalar that eases toward a target over time. Uses exponential
 * smoothing — {@code current += (target - current) * (1 - e^(-rate*dt))} —
 * which is frame-rate independent (the same wall-clock motion at any fps) and
 * never overshoots, so motion is a subtle one-off glide that settles rather
 * than a spring that wobbles.
 *
 * <p>Drives MortarUI hover/press/selected blends (0..1) and continuous values
 * like the contextual preview's slide-in width. Advance once per frame via
 * {@link #update(float)} after setting the {@link #setTarget(float) target}.</p>
 *
 * <p>Not thread-safe; intended for single-threaded UI use.</p>
 */
public final class Smoother {

    /** Default response rate (per second). Higher = snappier. */
    public static final float DEFAULT_RATE = 16.0f;

    /** Below this distance to target the value snaps, so it settles exactly. */
    private static final float SNAP_EPSILON = 0.0005f;

    private final float rate;
    private float current;
    private float target;

    public Smoother() {
        this(DEFAULT_RATE, 0.0f);
    }

    public Smoother(float rate) {
        this(rate, 0.0f);
    }

    public Smoother(float rate, float initial) {
        this.rate = rate > 0.0f ? rate : DEFAULT_RATE;
        this.current = initial;
        this.target = initial;
    }

    public void setTarget(float target) {
        this.target = target;
    }

    public float getTarget() {
        return target;
    }

    public float getValue() {
        return current;
    }

    /** Jump straight to a value with no animation (e.g. initial layout). */
    public void snapTo(float value) {
        this.current = value;
        this.target = value;
    }

    /**
     * Advance toward the target by {@code dt} seconds. Non-positive or
     * non-finite {@code dt} is ignored (e.g. a paused/first frame).
     */
    public void update(float dt) {
        if (dt <= 0.0f || !Float.isFinite(dt)) {
            return;
        }
        float diff = target - current;
        if (Math.abs(diff) <= SNAP_EPSILON) {
            current = target;
            return;
        }
        float alpha = 1.0f - (float) Math.exp(-rate * dt);
        current += diff * alpha;
        if (Math.abs(target - current) <= SNAP_EPSILON) {
            current = target;
        }
    }

    /** True once the value has reached its target. */
    public boolean isSettled() {
        return current == target;
    }
}
