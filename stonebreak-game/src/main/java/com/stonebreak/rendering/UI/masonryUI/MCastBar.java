package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * A progress bar with a <b>marked window</b> on the same timeline: a wind-up with a "react now"
 * span, a fishing or crafting sweet spot, a timed-input zone. The bar is {@code 0..total} seconds
 * wide, so the window is simply {@code from..to} mapped linearly onto it.
 *
 * <p>The window is a tinted zone drawn <em>over</em> the fill plus an opaque bracket that overshoots
 * the bar's top and bottom and carries a dark backing line, so it reads the same before the fill
 * reaches it and once the fill has covered it. It brightens while the timeline is inside it and can
 * {@link #pulse(boolean) pulse} (phase driven by {@link #update(float)}).
 *
 * <p>A label row sits above the bar when a {@link #label(String)} or {@link #windowLabel(String)}
 * is set. Everything {@link MGauge} offers (caption, value text, trail, flash) still applies.
 */
public class MCastBar extends MGauge {

    private static final float PULSE_RADIANS_PER_SECOND = 12f;
    private static final float TAU = (float) (Math.PI * 2.0);
    /** A short window must still be findable at a glance: never drawn thinner than this (design px). */
    private static final float MIN_MARKER_WIDTH = 4f;
    private static final float BRACKET_OVERSHOOT = 2f;
    private static final float BRACKET_THICKNESS = 1.5f;
    private static final float LABEL_ROW_PAD = 4f;

    private float elapsed;
    private float total;
    private boolean hasWindow;
    private float windowFrom;
    private float windowTo;

    private String label = "";
    private int labelColor = MStyle.TEXT_PRIMARY;
    private String windowLabel = "";
    private int windowColor = MStyle.TEXT_ACCENT;
    private boolean rampFill;
    private int fillStart, fillEnd;
    private boolean interrupted;
    private boolean pulse;
    private float pulsePhase;

    // ─────────────────────────────────────────────── Fluent

    /** Position on the timeline. A non-positive or non-finite {@code total} reads as an empty bar with no window. */
    public MCastBar timeline(float elapsed, float total) {
        this.total = Float.isFinite(total) ? Math.max(0f, total) : 0f;
        this.elapsed = Float.isFinite(elapsed) ? elapsed : 0f;
        super.fraction(this.total > 0f ? this.elapsed / this.total : 0f);
        return this;
    }

    /** The marked span in timeline seconds (either order; clamped to the timeline when drawn). NaN removes it. */
    public MCastBar window(float from, float to) {
        if (!Float.isFinite(from) || !Float.isFinite(to)) return clearWindow();
        this.hasWindow = true;
        this.windowFrom = Math.min(from, to);
        this.windowTo = Math.max(from, to);
        return this;
    }

    public MCastBar clearWindow() {
        this.hasWindow = false;
        this.windowFrom = 0f;
        this.windowTo = 0f;
        return this;
    }

    /** Small caption above the window, in the window colour. */
    public MCastBar windowLabel(String text) { this.windowLabel = text == null ? "" : text; return this; }

    public MCastBar windowColor(int color) { this.windowColor = color; return this; }

    /** Name of what is in progress, drawn above the bar's left end. */
    public MCastBar label(String text) { this.label = text == null ? "" : text; return this; }

    public MCastBar labelColor(int color) { this.labelColor = color; return this; }

    /** Fill colour shifts from {@code start} to {@code end} along the progress. */
    public MCastBar fillRamp(int start, int end) {
        this.rampFill = true;
        this.fillStart = start;
        this.fillEnd = end;
        return this;
    }

    /** Pulse the window marker (someone is poised to use it). */
    public MCastBar pulse(boolean enabled) {
        this.pulse = enabled;
        if (!enabled) pulsePhase = 0f;
        return this;
    }

    /** Greys the fill, window and labels out: the progress was cut short and the window no longer counts. */
    public MCastBar interrupted(boolean v) { this.interrupted = v; return this; }

    // Covariant returns keep fluent chains typed as MCastBar whatever order the calls come in.
    @Override public MCastBar value(float v, float max) { super.value(v, max); return this; }
    @Override public MCastBar fraction(float f) { super.fraction(f); return this; }
    @Override public MCastBar snapTo(float f) { super.snapTo(f); return this; }
    @Override public MCastBar snapTo(float v, float max) { super.snapTo(v, max); return this; }
    @Override public MCastBar fillColor(int color) { super.fillColor(color); this.rampFill = false; return this; }
    @Override public MCastBar ramp(int low, int mid, int high) { super.ramp(low, mid, high); this.rampFill = false; return this; }
    @Override public MCastBar vitalRamp() { super.vitalRamp(); return this; }
    @Override public MCastBar shape(Shape s) { super.shape(s); return this; }
    @Override public MCastBar barHeight(float designPx) { super.barHeight(designPx); return this; }
    @Override public MCastBar caption(String text) { super.caption(text); return this; }
    @Override public MCastBar captionWidth(float designPx) { super.captionWidth(designPx); return this; }
    @Override public MCastBar valueText(String text) { super.valueText(text); return this; }
    @Override public MCastBar valueAsFraction() { super.valueAsFraction(); return this; }
    @Override public MCastBar valueAsPercent() { super.valueAsPercent(); return this; }
    @Override public MCastBar valuePlacement(ValuePlacement p) { super.valuePlacement(p); return this; }
    @Override public MCastBar valueWidth(float designPx) { super.valueWidth(designPx); return this; }
    @Override public MCastBar valueColor(int color) { super.valueColor(color); return this; }
    @Override public MCastBar ghost(boolean enabled) { super.ghost(enabled); return this; }
    @Override public MCastBar ghostTiming(float hold, float ease) { super.ghostTiming(hold, ease); return this; }
    @Override public MCastBar flash(int color) { super.flash(color); return this; }
    @Override public MCastBar flashSeconds(float seconds) { super.flashSeconds(seconds); return this; }
    @Override public MCastBar fullGlow(int color) { super.fullGlow(color); return this; }
    @Override public MCastBar shimmer(boolean enabled) { super.shimmer(enabled); return this; }
    @Override public MCastBar pulseFlash() { super.pulseFlash(); return this; }
    @Override public MCastBar position(float x, float y) { super.position(x, y); return this; }
    @Override public MCastBar size(float w, float h) { super.size(w, h); return this; }
    @Override public MCastBar bounds(float x, float y, float w, float h) { super.bounds(x, y, w, h); return this; }
    @Override public MCastBar scale(float scale) { super.scale(scale); return this; }
    @Override public MCastBar scaleText(boolean v) { super.scaleText(v); return this; }

    /** Back to an empty, unbound timeline. The window and every look setting are kept. */
    @Override
    public MCastBar reset() {
        super.reset();
        elapsed = 0f;
        total = 0f;
        return this;
    }

    // ─────────────────────────────────────────────── Animation

    @Override
    public void update(float dt) {
        super.update(dt);
        float step = Float.isFinite(dt) ? Math.max(0f, dt) : 0f;
        pulsePhase = pulse ? (pulsePhase + step * PULSE_RADIANS_PER_SECOND) % TAU : 0f;
    }

    @Override
    protected void clearAnimation() {
        super.clearAnimation();
        pulsePhase = 0f;
    }

    // ─────────────────────────────────────────────── State readout

    public float elapsed() { return elapsed; }
    public float total() { return total; }

    /** True when there is a drawable window: one was set and the timeline has a length. */
    public boolean hasWindow() { return hasWindow && total > 0f; }

    /** True while the timeline is inside the window, both edges included. Never while interrupted. */
    public boolean inWindow() {
        return hasWindow() && !interrupted && elapsed >= windowFrom && elapsed <= windowTo;
    }

    /** Marker pulse 0..1 (0 when pulsing is off). */
    public float pulseAmount() {
        return pulse ? 0.5f + 0.5f * (float) Math.sin(pulsePhase) : 0f;
    }

    // ─────────────────────────────────────────────── Geometry

    /**
     * Horizontal extent {@code {x0, x1}} of the window on the bar: the linear map of
     * {@code from..to} over {@code 0..total}, clamped to the bar. With no window, both ends sit at
     * the bar's left edge. Exact under the same condition as {@link #barRect()}.
     */
    public float[] windowSpan() { return windowSpan(barRect()); }

    /** {@link #windowSpan()} against the bar exactly as rendered. */
    public float[] windowSpan(MasonryUI ui) { return windowSpan(barRect(ui)); }

    /**
     * The span the marker is actually painted over: {@link #windowSpan()} widened to a findable
     * minimum width (kept inside the bar) when the window is very short.
     */
    public float[] markerSpan() { return markerSpan(barRect()); }

    public float[] markerSpan(MasonryUI ui) { return markerSpan(barRect(ui)); }

    private float[] windowSpan(float[] bar) {
        if (!hasWindow()) return new float[]{bar[0], bar[0]};
        float a = MColor.clamp01(windowFrom / total);
        float b = MColor.clamp01(windowTo / total);
        return new float[]{bar[0] + bar[2] * a, bar[0] + bar[2] * b};
    }

    private float[] markerSpan(float[] bar) {
        float[] span = windowSpan(bar);
        float minW = Math.min(bar[2], MIN_MARKER_WIDTH * textScale());
        if (hasWindow() && span[1] - span[0] < minW) {
            span[0] = Math.max(bar[0], Math.min(span[0], bar[0] + bar[2] - minW));
            span[1] = span[0] + minW;
        }
        return span;
    }

    private boolean hasLabelRow() {
        return !label.isEmpty() || !windowLabel.isEmpty();
    }

    private float labelRowHeight() {
        if (!hasLabelRow()) return 0f;
        return Math.min((MStyle.FONT_META + LABEL_ROW_PAD) * textScale(), Math.max(0f, height) * 0.55f);
    }

    // The bracket (and its dark backing line) overshoots the bar, so the bar row keeps that much
    // clear above and below it and the widget never paints outside its bounds.
    private float markerMargin() {
        float s = textScale();
        return BRACKET_OVERSHOOT * s + Math.max(1f, s);
    }

    @Override
    protected float topInset() { return labelRowHeight() + markerMargin(); }

    @Override
    protected float bottomInset() { return markerMargin(); }

    // ─────────────────────────────────────────────── Render

    @Override
    protected int resolveFillColor(float f) {
        if (interrupted) return MStyle.TEXT_DISABLED;
        return rampFill ? MColor.lerp(fillStart, fillEnd, f) : super.resolveFillColor(f);
    }

    @Override
    protected void paintOverlay(MasonryUI ui, Canvas canvas, float[] bar) {
        float s = textScale();
        float[] span = markerSpan(bar);
        if (hasWindow()) paintMarker(canvas, bar, span, s);
        if (hasLabelRow()) paintLabels(ui, canvas, bar, span, s);
    }

    private void paintMarker(Canvas canvas, float[] bar, float[] span, float s) {
        float x0 = span[0], x1 = span[1];
        float over = BRACKET_OVERSHOOT * s;
        float t = Math.max(1.5f, BRACKET_THICKNESS * s);
        float d = Math.max(1f, s);                      // dark backing line around the bracket
        int edge = interrupted ? MStyle.TEXT_DISABLED : windowColor;
        float zone = interrupted ? 0.25f : (inWindow() ? 0.55f : 0.35f);
        int fill = MColor.lerp(MColor.fade(edge, zone), edge, interrupted ? 0f : pulseAmount() * 0.6f);

        float top = bar[1] - over, tall = bar[3] + 2f * over, wide = x1 - x0;
        rect(canvas, x0, bar[1], wide, bar[3], fill);

        // Dark backing, kept inside the bar's width so a window at either end stays in bounds.
        int backing = MStyle.OUTLINE_DARK;
        float bl = Math.max(bar[0], x0 - d), br = Math.min(bar[0] + bar[2], x1 + d);
        rect(canvas, bl, top - d, x0 + t + d - bl, tall + 2f * d, backing);
        rect(canvas, x1 - t - d, top - d, br - (x1 - t - d), tall + 2f * d, backing);
        rect(canvas, bl, top - d, br - bl, t + 2f * d, backing);
        rect(canvas, bl, top + tall - t - d, br - bl, t + 2f * d, backing);

        rect(canvas, x0, top, t, tall, edge);
        rect(canvas, x1 - t, top, t, tall, edge);
        rect(canvas, x0, top, wide, t, edge);
        rect(canvas, x0, top + tall - t, wide, t, edge);
    }

    private void paintLabels(MasonryUI ui, Canvas canvas, float[] bar, float[] span, float s) {
        float rowH = labelRowHeight();
        if (!(rowH > 0f)) return;
        float rowCentre = y + rowH / 2f;
        float left = bar[0], right = bar[0] + bar[2];
        float nameEnd = left;

        if (!label.isEmpty()) {
            Font font = fit(ui, fontFor(ui, Math.min(MStyle.FONT_META, rowH / Math.max(0.01f, s) * 0.95f)),
                    label, bar[2]);
            if (font != null) {
                MPainter.drawText(canvas, label, left, MPainter.baselineFor(rowCentre, font.getSize()), font,
                        interrupted ? MStyle.TEXT_DISABLED : labelColor, MPainter.Align.LEFT);
                nameEnd = left + MPainter.measureWidth(font, label) + 8f * s;
            }
        }
        if (!windowLabel.isEmpty() && hasWindow()) {
            Font font = fontFor(ui, Math.min(MStyle.FONT_CAPTION, rowH / Math.max(0.01f, s) * 0.95f));
            if (font == null) return;
            // Centred over the marker, pushed right of the name and kept inside the bar.
            float w = MPainter.measureWidth(font, windowLabel);
            float maxX = right - w;
            float tx = Math.max(nameEnd, Math.min(maxX, (span[0] + span[1]) / 2f - w / 2f));
            if (tx <= maxX) {
                MPainter.drawText(canvas, windowLabel, tx, MPainter.baselineFor(rowCentre, font.getSize()), font,
                        interrupted ? MStyle.TEXT_DISABLED : windowColor, MPainter.Align.LEFT);
            }
        }
    }
}
