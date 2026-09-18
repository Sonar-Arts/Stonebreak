package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * A row of discrete pips: charges, combo points, ammo, a hit counter. The first {@link #filled(int)}
 * of {@link #count(int)} pips are lit in {@link #color(int)}, the rest show {@link #emptyColor(int)};
 * every pip carries a dark 1px outline.
 *
 * <p>Pips are {@link #pipSize(float)} design pixels square, shrunk evenly when the bounds are too
 * short or too narrow to hold them, and placed by {@link #align(Align)} within the bounds.
 * {@link #pipRect(int)} is the one formula both painting and overlays use.
 *
 * <p>Two one-shot effects, both advanced only by {@link #update(float)}: {@link #popLast()} scales
 * the most recently filled pip up and lets it settle; {@link #spend()} flashes the pip(s) just
 * emptied. With {@link #autoAnimate(boolean)} on, {@link #filled(int)} fires them itself.
 */
public class MPipRow extends MWidget {

    public enum Shape { DIAMOND, RECT, CIRCLE }

    public enum Align { LEFT, CENTER, RIGHT }

    public static final float POP_SECONDS = 0.3f;
    public static final float SPEND_SECONDS = 0.4f;
    /** How far a popping pip grows at the peak, as a share of its size. */
    public static final float POP_GROWTH = 0.35f;

    private int count;
    private int filled;
    private boolean bound;
    private int color = MStyle.TEXT_ACCENT;
    private int emptyColor = MStyle.GAUGE_TRACK;
    private int spendFlashColor = MStyle.TEXT_PRIMARY;
    private Shape shape = Shape.DIAMOND;
    private Align align = Align.LEFT;
    private float pipSize = 12f;
    private float gap = 4f;
    private boolean autoAnimate;

    private float popAge = Float.MAX_VALUE;
    private float spendAge = Float.MAX_VALUE;
    private int spendFirst;
    private int spendCount;

    // ─────────────────────────────────────────────── Fluent

    public MPipRow count(int n) { this.count = Math.max(0, n); return this; }

    /**
     * How many pips are lit (clamped to the count when drawn). With {@link #autoAnimate(boolean)} on,
     * a gain pops the newest pip and a loss flashes the emptied ones; the first call never animates.
     */
    public MPipRow filled(int n) {
        int before = filledClamped();
        this.filled = Math.max(0, n);
        int after = filledClamped();
        if (autoAnimate && bound) {
            if (after > before) popLast();
            else if (after < before) spend(before - after);
        }
        bound = true;
        return this;
    }

    public MPipRow color(int c) { this.color = c; return this; }
    public MPipRow emptyColor(int c) { this.emptyColor = c; return this; }
    public MPipRow spendFlashColor(int c) { this.spendFlashColor = c; return this; }
    public MPipRow shape(Shape s) { this.shape = s == null ? Shape.DIAMOND : s; return this; }
    public MPipRow align(Align a) { this.align = a == null ? Align.LEFT : a; return this; }

    /** Pip side in design pixels. */
    public MPipRow pipSize(float designPx) { this.pipSize = positive(designPx); return this; }

    /** Space between pips in design pixels. */
    public MPipRow gap(float designPx) { this.gap = positive(designPx); return this; }

    public MPipRow autoAnimate(boolean v) { this.autoAnimate = v; return this; }

    /** Scale-pop on the most recently filled pip (call after raising {@link #filled(int)}). */
    public MPipRow popLast() { this.popAge = 0f; return this; }

    /** Flash on the pip just emptied (call after lowering {@link #filled(int)}). */
    public MPipRow spend() { return spend(1); }

    /** Flash on the {@code n} pips just emptied: the ones right after the last lit pip. */
    public MPipRow spend(int n) {
        if (n <= 0) return this;
        this.spendAge = 0f;
        this.spendFirst = filledClamped();
        this.spendCount = n;
        return this;
    }

    /** Stops both effects and forgets the binding, so the next {@link #filled(int)} does not animate. */
    public MPipRow reset() {
        popAge = Float.MAX_VALUE;
        spendAge = Float.MAX_VALUE;
        spendFirst = 0;
        spendCount = 0;
        bound = false;
        return this;
    }

    @Override public MPipRow position(float x, float y) { super.position(x, y); return this; }
    @Override public MPipRow size(float w, float h) { super.size(w, h); return this; }
    @Override public MPipRow bounds(float x, float y, float w, float h) { super.bounds(x, y, w, h); return this; }
    @Override public MPipRow scale(float scale) { super.scale(scale); return this; }
    @Override public MPipRow scaleText(boolean v) { super.scaleText(v); return this; }

    // ─────────────────────────────────────────────── Animation

    /** Advances the pop and the spend flash by {@code dt} seconds (negative/NaN = 0). */
    public void update(float dt) {
        float step = Float.isFinite(dt) ? Math.max(0f, dt) : 0f;
        popAge = popAge >= Float.MAX_VALUE - step ? Float.MAX_VALUE : popAge + step;
        spendAge = spendAge >= Float.MAX_VALUE - step ? Float.MAX_VALUE : spendAge + step;
    }

    /** Pop strength: 1 at {@link #popLast()}, easing to 0 over {@link #POP_SECONDS}. */
    public float popAmount() {
        float p = pulse(popAge, POP_SECONDS);
        return p * p;
    }

    /** Spend flash strength: 1 at {@link #spend()}, easing to 0 over {@link #SPEND_SECONDS}. */
    public float spendAmount() { return pulse(spendAge, SPEND_SECONDS); }

    private static float pulse(float age, float seconds) {
        if (age >= seconds) return 0f;
        return 1f - EasingFunctions.apply(age / seconds, EasingType.EaseOutCubic);
    }

    // ─────────────────────────────────────────────── Geometry

    public int count() { return count; }

    /** Lit pips, clamped to the count. */
    public int filled() { return filledClamped(); }

    /** Width the row wants at its configured pip size and gap, already scaled. */
    public float preferredWidth() {
        if (count <= 0) return 0f;
        float s = textScale();
        return count * pipSize * s + (count - 1) * gap * s;
    }

    /** Height the row wants (one pip), already scaled. */
    public float preferredHeight() { return pipSize * textScale(); }

    /** Side of one pip as drawn: the configured size, shrunk to fit the bounds. 0 = nothing to draw. */
    public float pipSide() {
        if (count <= 0 || !boundsUsable()) return 0f;
        float s = textScale();
        float side = Math.min(pipSize * s, height);
        side = Math.min(side, (width - (count - 1) * gap * s) / count);
        return side > 0f ? side : 0f;
    }

    /** Rect {@code {x, y, size, size}} of pip {@code index} at rest (no pop), or all zeros when there is none. */
    public float[] pipRect(int index) {
        float side = pipSide();
        if (index < 0 || index >= count || side <= 0f) return new float[]{0f, 0f, 0f, 0f};
        float pitch = side + gap * textScale();
        float total = count * side + (count - 1) * gap * textScale();
        float startX = switch (align) {
            case LEFT -> x;
            case CENTER -> x + (width - total) / 2f;
            case RIGHT -> x + width - total;
        };
        return new float[]{startX + index * pitch, y + (height - side) / 2f, side, side};
    }

    private boolean boundsUsable() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(width) && Float.isFinite(height)
                && width > 0f && height > 0f;
    }

    private int filledClamped() { return Math.max(0, Math.min(count, filled)); }

    private static float positive(float v) { return Float.isFinite(v) ? Math.max(0f, v) : 0f; }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui == null ? null : ui.canvas();
        if (canvas == null || pipSide() <= 0f) return;
        float s = textScale();
        int lit = filledClamped();
        float pop = popAmount();
        float spent = spendAmount();

        for (int i = 0; i < count; i++) {
            float[] r = pipRect(i);
            // The newest pip is the one that pops.
            float grow = (i == lit - 1) ? pop * r[2] * POP_GROWTH : 0f;
            float cx = r[0] + r[2] / 2f, cy = r[1] + r[3] / 2f;
            paintPip(canvas, cx, cy, (r[2] + grow) / 2f, i < lit, s);
            if (spent > 0f && i >= lit && i >= spendFirst && i < spendFirst + spendCount) {
                float radius = r[2] / 2f * (1f + 0.3f * (1f - spent));
                silhouette(canvas, cx, cy, radius, MColor.fade(spendFlashColor, spent * spent));
            }
        }
    }

    private void paintPip(Canvas canvas, float cx, float cy, float r, boolean lit, float s) {
        float o = Math.max(1f, s);
        // A diamond's edges run at 45°, so the same radius inset leaves a thinner rim: widen it.
        float inset = shape == Shape.DIAMOND ? o * 1.5f : o;
        float inner = Math.max(0.5f, r - inset);
        silhouette(canvas, cx, cy, r, MStyle.GAUGE_OUTLINE);
        silhouette(canvas, cx, cy, inner, lit ? color : emptyColor);
        if (!lit || r <= 3f * s) return;
        if (shape == Shape.RECT) {
            fillRect(canvas, cx - inner, cy - inner, inner * 2f, Math.max(1f, inner * 0.6f), MStyle.PANEL_HIGHLIGHT);
        } else {
            silhouette(canvas, cx, cy - r * 0.22f, r * 0.3f, MColor.withAlpha(MStyle.TEXT_PRIMARY, 0.4f));
        }
    }

    /** One pip silhouette of half-extent {@code r} centred on {@code (cx, cy)}. */
    private void silhouette(Canvas canvas, float cx, float cy, float r, int c) {
        if (!(r > 0f) || (c & 0xFF000000) == 0) return;
        switch (shape) {
            case RECT -> fillRect(canvas, cx - r, cy - r, r * 2f, r * 2f, c);
            case CIRCLE -> MPainter.fillCircle(canvas, cx, cy, r, c);
            case DIAMOND -> {
                try (PathBuilder pb = new PathBuilder()) {
                    pb.moveTo(cx, cy - r);
                    pb.lineTo(cx + r, cy);
                    pb.lineTo(cx, cy + r);
                    pb.lineTo(cx - r, cy);
                    pb.closePath();
                    try (Path path = pb.build(); Paint p = new Paint().setColor(c).setAntiAlias(true)) {
                        canvas.drawPath(path, p);
                    }
                }
            }
        }
    }

    private static void fillRect(Canvas canvas, float x, float y, float w, float h, int c) {
        if (!(w > 0f) || !(h > 0f) || (c & 0xFF000000) == 0) return;
        MPainter.fillRect(canvas, x, y, w, h, c);
    }
}
