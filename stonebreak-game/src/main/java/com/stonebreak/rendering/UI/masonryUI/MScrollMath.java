package com.stonebreak.rendering.UI.masonryUI;

/**
 * Pure scroll math: momentum, smoothing, clamping. Stateful but reusable.
 *
 * Ported from the settings-menu {@code ScrollManager}. Constants are
 * instance-configurable so future menus can tweak feel without editing
 * global configuration.
 */
public final class MScrollMath {

    private float scrollOffset;
    private float targetOffset;
    private float velocity;
    private float viewportHeight;
    private float contentHeight;
    private float maxOffset;
    private float padding;

    private float wheelSensitivity = 30f;
    private float velocityFactor = 0.8f;
    private float velocityDecay = 0.85f;
    private float lerpSpeed = 8.0f;

    public MScrollMath() {}

    public MScrollMath wheelSensitivity(float v) { this.wheelSensitivity = v; return this; }
    public MScrollMath velocityFactor(float v)   { this.velocityFactor = v;   return this; }
    public MScrollMath velocityDecay(float v)    { this.velocityDecay = v;    return this; }
    public MScrollMath lerpSpeed(float v)        { this.lerpSpeed = v;        return this; }
    public MScrollMath padding(float v)          { this.padding = v;          return this; }

    public void reset() {
        scrollOffset = 0f;
        targetOffset = 0f;
        velocity = 0f;
        viewportHeight = 0f;
        contentHeight = 0f;
        maxOffset = 0f;
    }

    public void updateBounds(float viewportHeight, float contentHeight) {
        this.viewportHeight = viewportHeight;
        this.contentHeight = contentHeight;
        this.maxOffset = Math.max(0f, contentHeight - viewportHeight + padding * 2f);
        clamp();
    }

    public void handleWheel(float delta) {
        float step = delta * wheelSensitivity;
        targetOffset -= step;
        targetOffset = Math.max(0f, Math.min(targetOffset, maxOffset));
        velocity = step * velocityFactor;
    }

    public void update(float deltaTime) {
        if (Math.abs(targetOffset - scrollOffset) > 0.5f) {
            float lerpFactor = Math.min(1.0f, deltaTime * lerpSpeed);
            scrollOffset += (targetOffset - scrollOffset) * lerpFactor;
            scrollOffset += velocity * deltaTime;
            velocity *= velocityDecay;
            clamp();
        } else {
            scrollOffset = targetOffset;
            velocity = 0f;
        }
    }

    public void scrollTo(float offset) {
        scrollOffset = Math.max(0f, Math.min(offset, maxOffset));
        targetOffset = scrollOffset;
        velocity = 0f;
    }

    private void clamp() {
        scrollOffset = Math.max(0f, Math.min(scrollOffset, maxOffset));
        targetOffset = Math.max(0f, Math.min(targetOffset, maxOffset));
    }

    public float offset() { return scrollOffset; }
    public float maxOffset() { return maxOffset; }
    public float viewportHeight() { return viewportHeight; }
    public float contentHeight() { return contentHeight; }
    public boolean isScrollNeeded() { return contentHeight > viewportHeight - padding * 2f; }
}
