package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * A meter bar any screen can use: health, stamina, a boss bar, crafting progress, a cooldown.
 *
 * <p>Layout, left to right inside the bounds: an optional caption column, the bar, and an optional
 * value column (the value can also sit over the bar). The bar is a {@link MStyle#GAUGE_TRACK} track
 * with a 1px {@link MStyle#GAUGE_OUTLINE}, a fill with a soft top sheen, and four opt-in effects:
 * <ul>
 *   <li>{@link #ghost ghost}: a pale trail that holds where the fill was before a drop, then eases
 *       down to it. A second drop during the hold keeps the trail's head and restarts the hold; a
 *       rise snaps the trail away (it only ever trails losses).</li>
 *   <li>{@link #flash flash}: a one-shot full-bar flash, fired with {@link #pulseFlash()}.</li>
 *   <li>{@link #fullGlow fullGlow}: an outline and fill blink while the gauge is full ("ready").</li>
 *   <li>{@link #shimmer shimmer}: a highlight band sweeping across the fill.</li>
 * </ul>
 *
 * <p>Animation state is per instance and advances only through {@link #update(float)}; painting
 * never mutates it, so one gauge is made per thing shown and two gauges fed the same calls paint
 * identically. Every metric is multiplied by {@link #textScale()}.
 */
public class MGauge extends MWidget {

    public enum Shape { RECT, PILL }

    /** Where the value text goes: over the bar (right or centre) or in its own column beside it. */
    public enum ValuePlacement { OVER_RIGHT, OVER_CENTER, BESIDE }

    private enum ValueMode { NONE, TEXT, FRACTION, PERCENT }

    public static final float DEFAULT_GHOST_HOLD_SECONDS = 0.35f;
    public static final float DEFAULT_GHOST_EASE_SECONDS = 0.5f;
    public static final float DEFAULT_FLASH_SECONDS = 0.3f;

    private static final float EPSILON = 1.0e-6f;
    private static final float COLUMN_GAP = 6f;
    private static final float GLOW_RADIANS_PER_SECOND = 5f;
    private static final float SHIMMER_SWEEPS_PER_SECOND = 0.7f;
    private static final float TAU = (float) (Math.PI * 2.0);

    // ─────────────────────────────────────────────── Data
    private float fraction;
    private float value;
    private float max;
    private boolean hasValue;
    /** False until the first value arrives: the first binding never animates. */
    private boolean bound;

    // ─────────────────────────────────────────────── Look
    private int fillColor = MStyle.SLIDER_FILL;
    private boolean ramp;
    private int rampLow, rampMid, rampHigh;
    private Shape shape = Shape.RECT;
    private float barHeight;

    private String caption = "";
    private float captionWidth;
    private ValueMode valueMode = ValueMode.NONE;
    private String valueText = "";
    private ValuePlacement valuePlacement = ValuePlacement.OVER_RIGHT;
    private float valueWidth;
    private int valueColor = MStyle.TEXT_PRIMARY;

    // ─────────────────────────────────────────────── Effects
    private boolean ghostEnabled;
    private float holdSeconds = DEFAULT_GHOST_HOLD_SECONDS;
    private float easeSeconds = DEFAULT_GHOST_EASE_SECONDS;
    private float ghost = -1f;
    private float hold;
    private boolean easing;
    private float easeFrom;
    private float easeTime;

    private int flashColor = MStyle.TEXT_PRIMARY;
    private float flashSeconds = DEFAULT_FLASH_SECONDS;
    private float flashAge = Float.MAX_VALUE;

    private int glowColor;
    private float glowPhase;

    private boolean shimmer;
    private float shimmerPhase;

    // ─────────────────────────────────────────────── Fluent: data

    /** {@code v} out of {@code max}; a non-positive or non-finite {@code max} reads as empty. */
    public MGauge value(float v, float max) {
        this.value = Float.isFinite(v) ? v : 0f;
        this.max = Float.isFinite(max) ? max : 0f;
        this.hasValue = true;
        applyFraction(this.max > 0f ? this.value / this.max : 0f);
        return this;
    }

    /** Fill level 0..1 (clamped; NaN reads as 0). */
    public MGauge fraction(float f) {
        this.hasValue = false;
        applyFraction(f);
        return this;
    }

    /** Sets the level with no animation: clears the trail, flash and blink (rebinding to a new subject). */
    public MGauge snapTo(float f) {
        this.hasValue = false;
        this.fraction = sanitize(f);
        this.bound = true;
        clearAnimation();
        return this;
    }

    /** {@link #snapTo(float)} for a value/max pair. */
    public MGauge snapTo(float v, float max) {
        this.value = Float.isFinite(v) ? v : 0f;
        this.max = Float.isFinite(max) ? max : 0f;
        this.hasValue = true;
        this.fraction = sanitize(this.max > 0f ? this.value / this.max : 0f);
        this.bound = true;
        clearAnimation();
        return this;
    }

    /** Back to an empty, unbound gauge: the next value is taken as-is, without a trail. */
    public MGauge reset() {
        fraction = 0f;
        value = 0f;
        max = 0f;
        hasValue = false;
        bound = false;
        clearAnimation();
        return this;
    }

    // ─────────────────────────────────────────────── Fluent: look

    public MGauge fillColor(int color) { this.fillColor = color; this.ramp = false; return this; }

    /** Colour the fill by its level: {@code low} near empty, through {@code mid}, to {@code high}. */
    public MGauge ramp(int low, int mid, int high) {
        this.ramp = true;
        this.rampLow = low;
        this.rampMid = mid;
        this.rampHigh = high;
        return this;
    }

    /** The shared health ramp ({@link MStyle#VITAL_CRIT} → {@link MStyle#VITAL_WARN} → {@link MStyle#VITAL_OK}). */
    public MGauge vitalRamp() { return ramp(MStyle.VITAL_CRIT, MStyle.VITAL_WARN, MStyle.VITAL_OK); }

    public MGauge shape(Shape s) { this.shape = s == null ? Shape.RECT : s; return this; }

    /** Cap on the bar's height in design pixels, centred in the bounds; 0 = fill the bounds. */
    public MGauge barHeight(float designPx) { this.barHeight = positive(designPx); return this; }

    /** Caption drawn left of the bar in {@link MStyle#TEXT_SECONDARY}. */
    public MGauge caption(String text) { this.caption = text == null ? "" : text; return this; }

    /**
     * Width reserved for the caption column in design pixels, so stacked gauges line their bars up.
     * 0 = as wide as the caption itself (measured; see {@link #barRect(MasonryUI)}).
     */
    public MGauge captionWidth(float designPx) { this.captionWidth = positive(designPx); return this; }

    /** Literal value text; {@code null} removes it. */
    public MGauge valueText(String text) {
        this.valueText = text == null ? "" : text;
        this.valueMode = text == null ? ValueMode.NONE : ValueMode.TEXT;
        return this;
    }

    /** Value text as "142/180" (from {@link #value(float, float)}; a bare fraction reads "NN/100"). */
    public MGauge valueAsFraction() { this.valueMode = ValueMode.FRACTION; return this; }

    /** Value text as "79%". */
    public MGauge valueAsPercent() { this.valueMode = ValueMode.PERCENT; return this; }

    public MGauge valuePlacement(ValuePlacement p) {
        this.valuePlacement = p == null ? ValuePlacement.OVER_RIGHT : p;
        return this;
    }

    /** Width of the {@link ValuePlacement#BESIDE} column in design pixels; 0 = measured. */
    public MGauge valueWidth(float designPx) { this.valueWidth = positive(designPx); return this; }

    public MGauge valueColor(int color) { this.valueColor = color; return this; }

    // ─────────────────────────────────────────────── Fluent: effects

    public MGauge ghost(boolean enabled) {
        this.ghostEnabled = enabled;
        if (!enabled) clearGhost();
        return this;
    }

    /** Trail timing: how long it holds at the old level, then how long it takes to ease down. */
    public MGauge ghostTiming(float holdSeconds, float easeSeconds) {
        this.holdSeconds = positive(holdSeconds);
        this.easeSeconds = positive(easeSeconds);
        return this;
    }

    /** Colour of the one-shot flash fired by {@link #pulseFlash()}. */
    public MGauge flash(int color) { this.flashColor = color; return this; }

    public MGauge flashSeconds(float seconds) { this.flashSeconds = positive(seconds); return this; }

    /** Blinking outline + fill tint while the gauge is full; 0 turns it off. */
    public MGauge fullGlow(int color) { this.glowColor = color; return this; }

    public MGauge shimmer(boolean enabled) {
        this.shimmer = enabled;
        if (!enabled) shimmerPhase = 0f;
        return this;
    }

    /** Fires the flash; it decays to nothing over {@link #flashSeconds(float)} of {@link #update}. */
    public MGauge pulseFlash() { this.flashAge = 0f; return this; }

    // Covariant returns keep fluent chains typed as MGauge.
    @Override public MGauge position(float x, float y) { super.position(x, y); return this; }
    @Override public MGauge size(float w, float h) { super.size(w, h); return this; }
    @Override public MGauge bounds(float x, float y, float w, float h) { super.bounds(x, y, w, h); return this; }
    @Override public MGauge scale(float scale) { super.scale(scale); return this; }
    @Override public MGauge scaleText(boolean v) { super.scaleText(v); return this; }

    // ─────────────────────────────────────────────── Animation

    /** Advances the trail, flash, blink and shimmer by {@code dt} seconds (negative/NaN = 0). */
    public void update(float dt) {
        float step = Float.isFinite(dt) ? Math.max(0f, dt) : 0f;

        if (ghost >= 0f) {
            if (!easing) {
                hold -= step;
                if (hold <= 0f) {
                    easing = true;
                    easeFrom = ghost;
                    easeTime = -hold;   // a large dt carries its remainder into the ease
                    hold = 0f;
                }
            } else {
                easeTime += step;
            }
            if (easing) {
                float t = easeSeconds <= 0f ? 1f : Math.min(1f, easeTime / easeSeconds);
                ghost = easeFrom + (fraction - easeFrom) * EasingFunctions.apply(t, EasingType.EaseOutCubic);
                if (t >= 1f || ghost <= fraction + EPSILON) clearGhost();
            }
        }

        flashAge = flashAge >= Float.MAX_VALUE - step ? Float.MAX_VALUE : flashAge + step;
        glowPhase = glowColor != 0 && isFull() ? (glowPhase + step * GLOW_RADIANS_PER_SECOND) % TAU : 0f;
        shimmerPhase = shimmer ? (shimmerPhase + step * SHIMMER_SWEEPS_PER_SECOND) % 1f : 0f;
    }

    /** Drops every running effect. Subclasses with their own clocks extend this. */
    protected void clearAnimation() {
        clearGhost();
        flashAge = Float.MAX_VALUE;
        glowPhase = 0f;
        shimmerPhase = 0f;
    }

    private void clearGhost() {
        ghost = -1f;
        hold = 0f;
        easing = false;
        easeFrom = 0f;
        easeTime = 0f;
    }

    private void applyFraction(float f) {
        float next = sanitize(f);
        if (bound && ghostEnabled && next < fraction - EPSILON) {
            // A further drop during a trail keeps the trail's head and restarts the hold.
            ghost = Math.max(ghost, fraction);
            hold = holdSeconds;
            easing = false;
            easeTime = 0f;
        }
        // Any gain snaps the trail away: it only ever trails losses.
        boolean rose = bound && next > fraction + EPSILON;
        fraction = next;
        bound = true;
        if (ghost >= 0f && (rose || fraction >= ghost - EPSILON)) clearGhost();
    }

    // ─────────────────────────────────────────────── State readout

    /** The fill level being drawn, 0..1. */
    public float displayedFraction() { return fraction; }

    /** Head of the trail, 0..1, or -1 when there is none. */
    public float ghostFraction() { return ghost > fraction ? ghost : -1f; }

    /** 1 at {@link #pulseFlash()}, falling linearly to 0. */
    public float flashStrength() {
        if (flashSeconds <= 0f || flashAge >= flashSeconds) return 0f;
        return 1f - flashAge / flashSeconds;
    }

    public boolean isFull() { return fraction >= 1f - EPSILON; }

    /** Blink strength of the full-gauge glow: 0 unless full with a glow colour, else 0.4..1. */
    public float glowStrength() {
        if (glowColor == 0 || !isFull()) return 0f;
        return 0.7f + 0.3f * (float) Math.cos(glowPhase);
    }

    /** Position of the shimmer band across the fill, 0..1, or -1 when off. */
    public float shimmerPhase() { return shimmer ? shimmerPhase : -1f; }

    /** The value text as it would be drawn ("" when none). */
    public String resolvedValueText() {
        return switch (valueMode) {
            case NONE -> "";
            case TEXT -> valueText;
            case FRACTION -> hasValue
                    ? Math.round(Math.max(0f, value)) + "/" + Math.round(Math.max(0f, max))
                    : Math.round(fraction * 100f) + "/100";
            case PERCENT -> Math.round(fraction * 100f) + "%";
        };
    }

    // ─────────────────────────────────────────────── Geometry

    /**
     * The bar's rect {@code {x, y, w, h}} from explicit column widths only. Exact whenever the
     * caption and {@link ValuePlacement#BESIDE} columns have explicit widths (or are absent); with a
     * measured column use {@link #barRect(MasonryUI)}.
     */
    public float[] barRect() { return barRect(null); }

    /** The bar's rect {@code {x, y, w, h}} exactly as {@link #render} draws it (for overlays such as sparks). */
    public float[] barRect(MasonryUI ui) {
        if (!hasUsableBounds()) return new float[]{0f, 0f, 0f, 0f};
        float s = textScale();
        float top = y + topInset();
        float rowH = y + height - bottomInset() - top;
        float left = x + captionColumn(ui, s);
        float w = x + width - valueColumn(ui, s) - left;
        float h = barHeight > 0f ? Math.min(rowH, barHeight * s) : rowH;
        if (!(w > 0f) || !(h > 0f)) return new float[]{x, y, 0f, 0f};
        return new float[]{left, top + (rowH - h) / 2f, w, h};
    }

    /** Finite position and a finite, positive size: anything else draws nothing. */
    protected boolean hasUsableBounds() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(width) && Float.isFinite(height)
                && width > 0f && height > 0f;
    }

    /** Space a subclass keeps above the bar row (labels), in pixels. */
    protected float topInset() { return 0f; }

    /** Space a subclass keeps below the bar row, in pixels. */
    protected float bottomInset() { return 0f; }

    private float captionColumn(MasonryUI ui, float s) {
        if (captionWidth > 0f) return captionWidth * s;
        if (caption.isEmpty() || ui == null) return 0f;
        return MPainter.measureWidth(rowFont(ui, s), caption) + COLUMN_GAP * s;
    }

    private float valueColumn(MasonryUI ui, float s) {
        if (valuePlacement != ValuePlacement.BESIDE || valueMode == ValueMode.NONE) return 0f;
        if (valueWidth > 0f) return valueWidth * s;
        if (ui == null) return 0f;
        return MPainter.measureWidth(rowFont(ui, s), resolvedValueText()) + COLUMN_GAP * s;
    }

    /** {@link MStyle#FONT_META}, reduced when the row is too short to hold it. */
    private Font rowFont(MasonryUI ui, float s) {
        float rowH = height - topInset() - bottomInset();
        float base = Math.min(MStyle.FONT_META, rowH / Math.max(0.01f, s) * 0.85f);
        return fontFor(ui, Float.isFinite(base) ? base : MStyle.FONT_META);
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui == null ? null : ui.canvas();
        if (canvas == null || !hasUsableBounds()) return;
        float s = textScale();
        float[] bar = barRect(ui);
        float rowCentre = y + topInset() + (height - topInset() - bottomInset()) / 2f;
        Font font = rowFont(ui, s);

        if (!caption.isEmpty() && font != null) {
            Font fitted = captionWidth > 0f ? fit(ui, font, caption, captionWidth * s - COLUMN_GAP * s * 0.5f) : font;
            MPainter.drawText(canvas, caption, x, MPainter.baselineFor(rowCentre, fitted.getSize()), fitted,
                    MStyle.TEXT_SECONDARY, MPainter.Align.LEFT);
        }
        if (bar[2] > 0f && bar[3] > 0f) {
            paintBar(canvas, bar, s);
            paintOverlay(ui, canvas, bar);
        }
        paintValue(ui, canvas, bar, font, rowCentre, s);
    }

    /** Hook for subclasses: drawn over the finished bar (outline included), under the value text. */
    protected void paintOverlay(MasonryUI ui, Canvas canvas, float[] bar) { }

    /** Fill colour for level {@code f}; subclasses may substitute their own rule. */
    protected int resolveFillColor(float f) {
        return ramp ? MColor.ramp(f, rampLow, rampMid, rampHigh, 0.2f, 0.6f) : fillColor;
    }

    private void paintBar(Canvas canvas, float[] bar, float s) {
        float bx = bar[0], by = bar[1], w = bar[2], h = bar[3];
        float o = Math.max(1f, s);                       // outline / inset unit
        boolean pill = shape == Shape.PILL;
        float r = pill ? h / 2f : 0f;
        float f = fraction;
        float g = ghostFraction();
        float glow = glowStrength();

        int color = resolveFillColor(f);
        if (glow > 0f) color = MColor.lerp(color, glowColor, 0.5f * glow);

        if (pill) MPainter.fillRoundedRect(canvas, bx, by, w, h, r, MStyle.GAUGE_TRACK);
        else rect(canvas, bx, by, w, h, MStyle.GAUGE_TRACK);

        // A pill never draws a fill narrower than its own end caps (a sliver would distort).
        float fillW = f <= 0f ? 0f : (pill ? Math.max(h, w * f) : w * f);
        if (g >= 0f) {
            if (pill) MPainter.fillRoundedRect(canvas, bx, by, Math.max(h, w * g), h, r, MStyle.GAUGE_GHOST);
            else rect(canvas, bx + w * f, by, w * (g - f), h, MStyle.GAUGE_GHOST);
        }
        if (fillW > 0f) {
            if (pill) {
                MPainter.fillRoundedRect(canvas, bx, by, fillW, h, r, color);
                float sheenH = Math.max(1f, h * 0.3f);
                if (fillW - r * 1.2f > 0f) {
                    MPainter.fillRoundedRect(canvas, bx + r * 0.6f, by + o, fillW - r * 1.2f, sheenH, sheenH / 2f,
                            MStyle.PANEL_HIGHLIGHT);
                }
            } else {
                rect(canvas, bx, by, fillW, h, color);
                // A lighter top band gives the fill a little volume without a gradient shader.
                rect(canvas, bx, by, fillW, Math.max(1f, h * 0.3f), MStyle.PANEL_HIGHLIGHT);
            }
            if (shimmer) paintShimmer(canvas, bx, by, fillW, w, h, pill ? r * 0.5f : o, o);
        }

        float flash = flashStrength();
        if (flash > 0f) {
            int c = MColor.fade(flashColor, flash * 0.85f);
            if (pill) MPainter.fillRoundedRect(canvas, bx, by, w, h, r, c);
            else rect(canvas, bx, by, w, h, c);
        }

        if (w > o && h > o) {
            if (pill) MPainter.strokeRoundedRect(canvas, bx, by, w, h, r, MStyle.GAUGE_OUTLINE, o);
            else MPainter.strokeRect(canvas, bx + o / 2f, by + o / 2f, w - o, h - o, MStyle.GAUGE_OUTLINE, o);
        }
        if (glow > 0f) {
            MPainter.strokeRoundedRect(canvas, bx - o, by - o, w + 2f * o, h + 2f * o, pill ? r + o : s,
                    MColor.fade(glowColor, glow), o);
        }
    }

    private void paintShimmer(Canvas canvas, float bx, float by, float fillW, float barW, float h,
                              float sideInset, float o) {
        float bandW = barW * 0.12f;
        float start = bx + (fillW + bandW) * shimmerPhase - bandW;
        float left = Math.max(bx + sideInset, start);
        float right = Math.min(bx + fillW - sideInset, start + bandW);
        rect(canvas, left, by + o, right - left, h - 2f * o, MColor.withAlpha(MStyle.TEXT_PRIMARY, 0.4f));
    }

    private void paintValue(MasonryUI ui, Canvas canvas, float[] bar, Font font, float rowCentre, float s) {
        String text = resolvedValueText();
        if (text.isEmpty() || font == null) return;
        if (valuePlacement == ValuePlacement.BESIDE) {
            Font fitted = valueWidth > 0f ? fit(ui, font, text, valueWidth * s - COLUMN_GAP * s * 0.5f) : font;
            MPainter.drawText(canvas, text, x + width, MPainter.baselineFor(rowCentre, fitted.getSize()), fitted,
                    valueColor, MPainter.Align.RIGHT);
            return;
        }
        if (!(bar[2] > 0f)) return;
        Font fitted = fit(ui, font, text, bar[2] - 8f * s);
        float baseline = MPainter.baselineFor(bar[1] + bar[3] / 2f, fitted.getSize());
        // Outlined, not just shadowed: the text crosses fill, trail and track, and must read on all three.
        float outline = Math.max(2f, 2.5f * s);
        if (valuePlacement == ValuePlacement.OVER_CENTER) {
            MPainter.drawTextOutlined(canvas, text, bar[0] + bar[2] / 2f, baseline, fitted, valueColor,
                    MPainter.Align.CENTER, outline);
        } else {
            float pad = shape == Shape.PILL ? Math.max(4f * s, bar[3] * 0.3f) : 4f * s;
            MPainter.drawTextOutlined(canvas, text, bar[0] + bar[2] - pad, baseline, fitted, valueColor,
                    MPainter.Align.RIGHT, outline);
        }
    }

    // ─────────────────────────────────────────────── Helpers (shared with subclasses)

    /** {@code font}, or a smaller one when {@code text} would not fit {@code maxWidth}. */
    protected static Font fit(MasonryUI ui, Font font, String text, float maxWidth) {
        if (font == null || !(maxWidth > 0f) || MPainter.measureWidth(font, text) <= maxWidth) return font;
        Font smaller = ui.fonts().fit(text, font.getSize(), maxWidth, 0.6f);
        return smaller != null ? smaller : font;
    }

    /** Aliased rect fill that ignores empty, inverted, non-finite and fully transparent requests. */
    protected static void rect(Canvas canvas, float x, float y, float w, float h, int color) {
        if (canvas == null || !(w > 0f) || !(h > 0f) || (color & 0xFF000000) == 0) return;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(w) || !Float.isFinite(h)) return;
        MPainter.fillRect(canvas, x, y, w, h, color);
    }

    protected static float sanitize(float f) {
        return Float.isFinite(f) ? MColor.clamp01(f) : 0f;
    }

    protected static float positive(float v) {
        return Float.isFinite(v) ? Math.max(0f, v) : 0f;
    }
}
