package com.openmason.main.systems.mortar.anim;

/**
 * A one-off transition between two discrete states (e.g. the hub's Home↔Learn
 * view switch). When the {@link #to(Object) destination} changes, progress
 * resets to 0 and advances to 1 over a fixed duration; {@link #alpha()} gives
 * an eased 0→1 factor for fading/sliding the incoming content in.
 *
 * <p>Holds only the <em>current</em> and <em>previous</em> state keys plus a
 * progress scalar — callers decide how to paint the blend.</p>
 *
 * @param <T> the state key type (an enum, typically)
 */
public final class Crossfade<T> {

    private final float duration;

    private T current;
    private T previous;
    private float progress;

    public Crossfade(T initial, float durationSeconds) {
        this.current = initial;
        this.previous = initial;
        this.duration = durationSeconds > 0.0f ? durationSeconds : 0.2f;
        this.progress = 1.0f;
    }

    /**
     * Point the transition at {@code next}. If it differs from the current
     * destination the transition restarts from 0.
     */
    public void to(T next) {
        if (next == null || next.equals(current)) {
            return;
        }
        this.previous = this.current;
        this.current = next;
        this.progress = 0.0f;
    }

    public void update(float dt) {
        if (dt <= 0.0f || !Float.isFinite(dt) || progress >= 1.0f) {
            return;
        }
        progress = Easing.clamp01(progress + dt / duration);
    }

    public T current() {
        return current;
    }

    public T previous() {
        return previous;
    }

    public boolean isTransitioning() {
        return progress < 1.0f;
    }

    /** Eased incoming-content factor in [0,1]. */
    public float alpha() {
        return Easing.easeOutCubic(progress);
    }
}
