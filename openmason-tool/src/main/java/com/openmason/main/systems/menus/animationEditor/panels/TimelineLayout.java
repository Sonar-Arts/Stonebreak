package com.openmason.main.systems.menus.animationEditor.panels;

/**
 * Pure time↔pixel math for the timeline: zoom, horizontal scroll, frame
 * snapping. No ImGui dependency so it is unit-testable.
 *
 * <p>Model: at {@code zoom == 1} the whole clip fits the bar. The visible
 * window spans {@code duration / zoom} seconds starting at {@code scrollSec},
 * which is clamped so the window never leaves {@code [0, duration]}.
 */
public final class TimelineLayout {

    public static final float MIN_ZOOM = 1f;
    public static final float MAX_ZOOM = 100f;

    private final float x0;
    private final float width;
    private final float duration;
    private final float windowStart;
    private final float windowLen;

    public TimelineLayout(float x0, float width, float duration, float zoom, float scrollSec) {
        this.x0 = x0;
        this.width = Math.max(1f, width);
        this.duration = Math.max(1e-4f, duration);
        float z = clampZoom(zoom);
        this.windowLen = this.duration / z;
        this.windowStart = clampScroll(scrollSec, z, this.duration);
    }

    public float visibleStart() { return windowStart; }
    public float visibleEnd() { return windowStart + windowLen; }
    public float visibleLength() { return windowLen; }

    /** Screen x for a clip time. Times outside the window map off-bar. */
    public float timeToX(float t) {
        return x0 + (t - windowStart) / windowLen * width;
    }

    /** Clip time for a screen x, clamped to {@code [0, duration]}. */
    public float xToTime(float x) {
        float t = windowStart + (x - x0) / width * windowLen;
        return Math.min(Math.max(t, 0f), duration);
    }

    /** Seconds represented by a horizontal pixel delta (unclamped). */
    public float deltaXToDeltaTime(float dx) {
        return dx / width * windowLen;
    }

    public boolean isTimeVisible(float t) {
        return t >= windowStart - 1e-5f && t <= windowStart + windowLen + 1e-5f;
    }

    /** Snap a time to the nearest frame boundary at the given fps. */
    public static float snap(float t, float fps) {
        if (fps <= 0f) return t;
        return Math.round(t * fps) / fps;
    }

    public static float clampZoom(float zoom) {
        return Math.min(Math.max(zoom, MIN_ZOOM), MAX_ZOOM);
    }

    /** Clamp a scroll offset so the visible window stays within the clip. */
    public static float clampScroll(float scrollSec, float zoom, float duration) {
        float windowLen = duration / clampZoom(zoom);
        float max = Math.max(0f, duration - windowLen);
        return Math.min(Math.max(scrollSec, 0f), max);
    }

    /**
     * Scroll offset that keeps the time under {@code anchorX} stationary when
     * zoom changes — the standard zoom-at-mouse behavior.
     */
    public static float scrollForZoomAnchor(float anchorTime, float anchorX,
                                            float x0, float width,
                                            float newZoom, float duration) {
        float windowLen = duration / clampZoom(newZoom);
        float u = (anchorX - x0) / Math.max(1f, width);
        return clampScroll(anchorTime - u * windowLen, newZoom, duration);
    }
}
