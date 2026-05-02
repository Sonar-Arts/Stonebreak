package com.stonebreak.ui.startupIntro.tween;

public final class FloatTween extends Tween {

    private final float start;
    private final float end;
    private float current;

    public FloatTween(float start, float end, float duration, EasingType easing) {
        super(duration, easing);
        this.start = start;
        this.end = end;
        this.current = start;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        float p = progress();
        current = start + (end - start) * p;
    }

    public float current() {
        return current;
    }
}
