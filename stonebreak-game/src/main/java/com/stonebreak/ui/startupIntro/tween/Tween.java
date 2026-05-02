package com.stonebreak.ui.startupIntro.tween;

public abstract class Tween {

    protected float elapsed;
    protected final float duration;
    protected final EasingType easing;
    protected boolean complete;

    protected Tween(float duration, EasingType easing) {
        this.duration = duration;
        this.easing = easing;
    }

    public boolean isComplete() {
        return complete;
    }

    public void update(float deltaTime) {
        if (complete) return;
        elapsed += deltaTime;
        if (elapsed >= duration) {
            elapsed = duration;
            complete = true;
        }
    }

    protected float progress() {
        float t = duration <= 0f ? 1f : Math.min(elapsed / duration, 1f);
        return EasingFunctions.apply(t, easing);
    }
}
