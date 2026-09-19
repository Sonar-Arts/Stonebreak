package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * Full-screen effects any screen can lay under (or over) its widgets: cinematic letterbox bars, edge
 * vignettes (low health, cold, a charged state), a one-frame flash, a modal scrim and an expanding
 * ring. Stateless static painters: the caller owns every intensity and animates it through its own
 * {@code update(dt)}; nothing here reads a clock.
 *
 * <p>The scene has to stay readable underneath, so every vignette follows one curve
 * ({@link #vignetteAlpha}): exactly nothing inside its inner radius, a smooth rise to the peak at the
 * corners, and never more than {@link #VIGNETTE_MAX_ALPHA} anywhere. When several vignettes are up at
 * once, {@link #stackedVignette} scales their peaks down together so the <em>composite</em> respects
 * the same cap.
 *
 * <p>Radii are normalised and elliptical: 0 at the screen centre, 1 at the middle of each edge,
 * {@link #CORNER_RADIUS} (√2) in the corners.
 */
public final class MScreenFx {

    private MScreenFx() {}

    /** Hard cap: no vignette, alone or stacked, is ever more opaque than this, anywhere. */
    public static final float VIGNETTE_MAX_ALPHA = 0.45f;
    /** Normalised radius of the screen corners. */
    public static final float CORNER_RADIUS = (float) Math.sqrt(2.0);
    /** Share of the window height one letterbox bar takes when fully in (the usual cinematic bar). */
    public static final float LETTERBOX_FRACTION = 0.11f;

    /** The vignette reaches its peak this far short of the very corner, so the corner is a plateau. */
    private static final float PEAK_INSET = 0.06f;
    private static final float MIN_RAMP = 0.05f;
    private static final int GRADIENT_STOPS = 12;

    // ─────────────────────────────────────────────── Letterbox

    /** Height in pixels of one bar: {@code h × barFraction × amount}, rounded to a whole pixel. */
    public static float letterboxHeight(float h, float amount, float barFraction) {
        if (!(h > 0f)) return 0f;
        float fraction = finite(barFraction) ? Math.max(0f, Math.min(0.5f, barFraction)) : 0f;
        return Math.round(h * fraction * MColor.clamp01(finiteOr(amount, 0f)));
    }

    /** Top bar {@code {x, y, w, h}}; {@code amount} 0 = hidden, 1 = fully in. */
    public static float[] letterboxTopRect(float w, float h, float amount, float barFraction) {
        return new float[]{0f, 0f, Math.max(0f, finiteOr(w, 0f)), letterboxHeight(h, amount, barFraction)};
    }

    /** Bottom bar {@code {x, y, w, h}}. */
    public static float[] letterboxBottomRect(float w, float h, float amount, float barFraction) {
        float bar = letterboxHeight(h, amount, barFraction);
        return new float[]{0f, Math.max(0f, finiteOr(h, 0f)) - bar, Math.max(0f, finiteOr(w, 0f)), bar};
    }

    /** Opaque bars in {@code color} sliding in from the top and bottom edges. */
    public static void letterbox(Canvas canvas, float w, float h, float amount, float barFraction, int color) {
        if (canvas == null || !(w > 0f) || !(h > 0f)) return;
        float[] top = letterboxTopRect(w, h, amount, barFraction);
        if (top[3] <= 0f) return;
        float[] bottom = letterboxBottomRect(w, h, amount, barFraction);
        MPainter.fillRect(canvas, top[0], top[1], top[2], top[3], color);
        MPainter.fillRect(canvas, bottom[0], bottom[1], bottom[2], bottom[3], color);
    }

    /** Letterbox in the overlay colour at full opacity (the house black). */
    public static void letterbox(Canvas canvas, float w, float h, float amount, float barFraction) {
        letterbox(canvas, w, h, amount, barFraction, MColor.withAlpha(MStyle.OVERLAY_DEEP, 1f));
    }

    // ─────────────────────────────────────────────── Vignette maths (pure)

    /** Elliptical distance from the screen centre: 0 centre, 1 mid-edge, √2 corner. */
    public static float normalisedRadius(float x, float y, float w, float h) {
        if (!(w > 0f) || !(h > 0f)) return 0f;
        float dx = (x - w / 2f) / (w / 2f), dy = (y - h / 2f) / (h / 2f);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Vignette opacity at normalised radius {@code r}: exactly 0 out to {@code innerRadius}, then a
     * smoothstep rise to {@code peakAlpha} just short of the corners. {@code peakAlpha} is capped at
     * {@link #VIGNETTE_MAX_ALPHA}; NaN anywhere yields 0.
     */
    public static float vignetteAlpha(float r, float peakAlpha, float innerRadius) {
        if (!finite(r) || !finite(peakAlpha)) return 0f;
        float inner = clampInner(innerRadius);
        if (r <= inner) return 0f;
        float peak = Math.max(0f, Math.min(VIGNETTE_MAX_ALPHA, peakAlpha));
        float outer = Math.max(inner + MIN_RAMP, CORNER_RADIUS - PEAK_INSET);
        return peak * smoothstep((r - inner) / (outer - inner));
    }

    /** Opacity of several layers drawn over one another ({@code 1 − Π(1 − aᵢ)}). */
    public static float compositeAlpha(float... alphas) {
        if (alphas == null) return 0f;
        float through = 1f;
        for (float a : alphas) through *= 1f - MColor.clamp01(finiteOr(a, 0f));
        return 1f - through;
    }

    /**
     * {@code peaks}, each first capped at {@link #VIGNETTE_MAX_ALPHA}, then all scaled by one common
     * factor if drawing them over one another would still exceed the cap. Layers composite
     * multiplicatively, so the factor has no closed form; it is bisected (deterministic, 20 steps).
     */
    public static float[] capStack(float... peaks) {
        if (peaks == null) return new float[0];
        float[] capped = new float[peaks.length];
        for (int i = 0; i < peaks.length; i++) {
            capped[i] = Math.max(0f, Math.min(VIGNETTE_MAX_ALPHA, finiteOr(peaks[i], 0f)));
        }
        if (compositeAlpha(capped) <= VIGNETTE_MAX_ALPHA) return capped;
        float lo = 0f, hi = 1f;
        float[] trial = new float[capped.length];
        for (int step = 0; step < 20; step++) {
            float mid = (lo + hi) / 2f;
            for (int i = 0; i < capped.length; i++) trial[i] = capped[i] * mid;
            if (compositeAlpha(trial) > VIGNETTE_MAX_ALPHA) hi = mid; else lo = mid;
        }
        for (int i = 0; i < capped.length; i++) capped[i] *= lo;
        return capped;
    }

    // ─────────────────────────────────────────────── Vignette painters

    /** One elliptical edge vignette in {@code color} (its own alpha is ignored; the curve sets it). */
    public static void vignette(Canvas canvas, float w, float h, int color, float peakAlpha, float innerRadius) {
        if (canvas == null || !(w > 0f) || !(h > 0f) || !finite(peakAlpha) || peakAlpha <= 0.002f) return;
        float inner = clampInner(innerRadius);
        int[] colors = new int[GRADIENT_STOPS + 1];
        float[] positions = new float[GRADIENT_STOPS + 1];
        // First stop sits exactly on the inner radius, so the centre is untouched by construction.
        for (int i = 0; i <= GRADIENT_STOPS; i++) {
            float r = inner + (CORNER_RADIUS - inner) * i / GRADIENT_STOPS;
            positions[i] = r / CORNER_RADIUS;
            colors[i] = MColor.withAlpha(color, vignetteAlpha(r, peakAlpha, inner));
        }
        canvas.save();
        try {
            // Unit circle → screen ellipse: radius 1 touches the middle of each edge.
            canvas.translate(w / 2f, h / 2f);
            canvas.scale(w / 2f, h / 2f);
            try (Shader shader = Shader.makeRadialGradient(0f, 0f, CORNER_RADIUS, colors, positions);
                 Paint p = new Paint().setShader(shader)) {
                canvas.drawRect(Rect.makeLTRB(-1f, -1f, 1f, 1f), p);
            }
        } finally {
            canvas.restore();
        }
    }

    /**
     * Several vignettes at once, their peaks passed through {@link #capStack} so the composite never
     * exceeds the cap. Arrays are read up to the shortest length; a missing {@code innerRadii} means 0.5.
     */
    public static void stackedVignette(Canvas canvas, float w, float h, int[] colors, float[] peaks,
                                       float[] innerRadii) {
        if (canvas == null || colors == null || peaks == null) return;
        int n = Math.min(colors.length, peaks.length);
        if (innerRadii != null) n = Math.min(n, innerRadii.length);
        if (n <= 0) return;
        float[] capped = capStack(java.util.Arrays.copyOf(peaks, n));
        for (int i = 0; i < n; i++) {
            vignette(canvas, w, h, colors[i], capped[i], innerRadii == null ? 0.5f : innerRadii[i]);
        }
    }

    // ─────────────────────────────────────────────── Flash, scrim, ring

    /** Whole-screen wash of {@code color} at {@code alpha} (multiplied into the colour's own alpha). */
    public static void flash(Canvas canvas, float w, float h, int color, float alpha) {
        if (canvas == null || !(w > 0f) || !(h > 0f) || !(alpha > 0f)) return;
        int faded = MColor.fade(color, alpha);
        if ((faded & 0xFF000000) == 0) return;
        MPainter.fillRect(canvas, 0f, 0f, w, h, faded);
    }

    /** The dark veil behind a modal: {@link MStyle#OVERLAY_DEEP} at {@code alpha} of its strength. */
    public static void scrim(Canvas canvas, float w, float h, float alpha) {
        flash(canvas, w, h, MStyle.OVERLAY_DEEP, alpha);
    }

    /** A stroked circle for impact / ping rings; the caller grows {@code radius} and drops {@code alpha}. */
    public static void expandingRing(Canvas canvas, float cx, float cy, float radius, int color, float alpha,
                                     float width) {
        if (canvas == null || !finite(cx) || !finite(cy) || !(radius > 0f) || !(width > 0f) || !(alpha > 0f)) return;
        MPainter.strokeCircle(canvas, cx, cy, radius, MColor.fade(color, alpha), width);
    }

    // ─────────────────────────────────────────────── Helpers

    private static float clampInner(float innerRadius) {
        float inner = finiteOr(innerRadius, 0.5f);
        return Math.max(0f, Math.min(CORNER_RADIUS - PEAK_INSET - MIN_RAMP, inner));
    }

    private static float smoothstep(float t) {
        float k = MColor.clamp01(t);
        return k * k * (3f - 2f * k);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float finiteOr(float v, float fallback) {
        return finite(v) ? v : fallback;
    }
}
