package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * E14 — full-screen effects, drawn as an <b>underlay</b> so the HUD windows stay clean on top:
 * low-HP red vignette (a heartbeat that quickens as HP drops), frost vignette with corner crystals
 * while the monk is CHILLED, gold edge aura while Focus is full, a brief flash on critical hits, the
 * slow grey-dark fade of a defeat, and the cinematic letterbox bars.
 *
 * <p>The fight has to stay playable underneath, so every vignette follows one curve
 * ({@link TimedLayout#vignetteAlpha}): nothing at all over the centre half of the screen, never
 * more than {@link TimedLayout#VIGNETTE_MAX_ALPHA} anywhere — and when several are up at once their
 * peaks are scaled down together so the <em>stack</em> respects the same cap.
 */
public final class ScreenFxLayer {

    private ScreenFxLayer() {}

    static final float LOW_HP_PEAK = 0.44f;
    static final float FROST_PEAK = 0.42f;
    static final float FOCUS_PEAK = 0.30f;
    /** The focus aura hugs the edges: it starts further out than the other two. */
    static final float FOCUS_INNER_RADIUS = 0.82f;
    static final float CRIT_FLASH_PEAK = 0.22f;
    static final float DEFEAT_GREY_PEAK = 0.40f;
    static final float DEFEAT_DARK_PEAK = 0.45f;
    private static final int GRADIENT_STOPS = 12;

    public static void paint(Canvas canvas, int w, int h, float scale, BattleView view, TimedInputState state) {
        if (canvas == null || state == null || w <= 0 || h <= 0) return;
        float[] peaks = vignettePeaks(view, state);
        vignette(canvas, w, h, TimedTheme.FX_LOW_HP, peaks[0], TimedLayout.VIGNETTE_CLEAR_RADIUS);
        vignette(canvas, w, h, TimedTheme.FX_FROST, peaks[1], TimedLayout.VIGNETTE_CLEAR_RADIUS);
        if (state.frost > 0f) frostCrystals(canvas, w, h, scale, state.frost);
        vignette(canvas, w, h, TimedTheme.FX_FOCUS, peaks[2], FOCUS_INNER_RADIUS);

        if (state.critFlashAge >= 0f) {
            float env = TimedTheme.flashEnvelope(state.critFlashAge / TimedInputState.CRIT_FLASH_SECONDS);
            int color = state.critFlashTarget == CombatantId.MONK ? TimedTheme.FX_CRIT_TAKEN : TimedTheme.FX_CRIT_DEALT;
            FocusBattleTheme.fillRect(canvas, 0f, 0f, w, h, FocusBattleTheme.fade(color, CRIT_FLASH_PEAK * env));
        }
        if (state.defeatFade > 0f) {
            // A grey wash pulls every colour toward neutral (the desaturation), then the dark closes in.
            float k = TimedTheme.smoothstep(state.defeatFade);
            FocusBattleTheme.fillRect(canvas, 0f, 0f, w, h, FocusBattleTheme.fade(TimedTheme.FX_DEFEAT_GREY, DEFEAT_GREY_PEAK * k));
            FocusBattleTheme.fillRect(canvas, 0f, 0f, w, h,
                    FocusBattleTheme.fade(TimedTheme.FX_DEFEAT_DARK, DEFEAT_DARK_PEAK * k * k));
        }
        float bars = letterboxAmount(state);
        if (bars > 0f) {
            float[] top = FocusBattleLayout.letterboxTopRect(w, h, bars);
            float[] bottom = FocusBattleLayout.letterboxBottomRect(w, h, bars);
            FocusBattleTheme.fillRect(canvas, top[0], top[1], top[2], top[3], TimedTheme.FX_LETTERBOX);
            FocusBattleTheme.fillRect(canvas, bottom[0], bottom[1], bottom[2], bottom[3], TimedTheme.FX_LETTERBOX);
        }
    }

    /** Eased bar amount 0..1 handed to the layout's letterbox rects. */
    public static float letterboxAmount(TimedInputState state) {
        return TimedTheme.smoothstep(state.letterbox);
    }

    /**
     * Peak (corner) alpha of {@code {lowHp, frost, focus}} this frame. Each follows its own eased
     * intensity and pulse; if the three composited would exceed the cap they are scaled down together.
     */
    public static float[] vignettePeaks(BattleView view, TimedInputState state) {
        float severity = TimedInputState.lowHpSeverity(view);
        float beat = 0.5f + 0.5f * (float) Math.sin(state.lowHpPhase);
        // Deeper swing the closer to death: barely breathing at the threshold, thumping near zero.
        float lowHp = state.lowHp * LOW_HP_PEAK * (0.62f + (0.18f + 0.2f * severity) * beat);
        float frost = state.frost * FROST_PEAK;
        float focus = state.focusAura * FOCUS_PEAK * (0.75f + 0.25f * (0.5f + 0.5f * (float) Math.sin(state.time * 3.2)));
        if (stacked(lowHp, frost, focus, 1f) > TimedLayout.VIGNETTE_MAX_ALPHA) {
            // Layers composite multiplicatively, so the common scale has no closed form; bisect it.
            float lo = 0f, hi = 1f;
            for (int i = 0; i < 16; i++) {
                float mid = (lo + hi) / 2f;
                if (stacked(lowHp, frost, focus, mid) > TimedLayout.VIGNETTE_MAX_ALPHA) hi = mid; else lo = mid;
            }
            lowHp *= lo;
            frost *= lo;
            focus *= lo;
        }
        return new float[]{lowHp, frost, focus};
    }

    /** Opacity of the three layers drawn over one another, each scaled by {@code k}. */
    static float stacked(float a, float b, float c, float k) {
        return 1f - (1f - a * k) * (1f - b * k) * (1f - c * k);
    }

    /** One elliptical vignette: a radial gradient sampled from the shared alpha curve. */
    private static void vignette(Canvas canvas, int w, int h, int color, float peak, float innerRadius) {
        if (peak <= 0.002f) return;
        int[] colors = new int[GRADIENT_STOPS + 1];
        float[] positions = new float[GRADIENT_STOPS + 1];
        float inner = Math.max(TimedLayout.VIGNETTE_CLEAR_RADIUS, innerRadius);
        // First stop sits exactly on the clear radius, so the centre is untouched by construction.
        for (int i = 0; i <= GRADIENT_STOPS; i++) {
            float r = inner + (TimedLayout.VIGNETTE_CORNER_RADIUS - inner) * i / GRADIENT_STOPS;
            positions[i] = r / TimedLayout.VIGNETTE_CORNER_RADIUS;
            colors[i] = FocusBattleTheme.fade(color | 0xFF000000, TimedLayout.vignetteAlpha(r, peak, inner));
        }
        canvas.save();
        try {
            // Unit circle → screen ellipse: radius 1 touches the middle of each edge.
            canvas.translate(w / 2f, h / 2f);
            canvas.scale(w / 2f, h / 2f);
            try (Shader shader = Shader.makeRadialGradient(0f, 0f, TimedLayout.VIGNETTE_CORNER_RADIUS, colors, positions);
                 Paint p = new Paint().setShader(shader)) {
                canvas.drawRect(Rect.makeLTRB(-1f, -1f, 1f, 1f), p);
            }
        } finally {
            canvas.restore();
        }
    }

    // ─────────────────────────────────────────────── Frost crystals

    // Shards fanning out of a corner: {angle as a fraction of the 90° sweep, length, half-width},
    // lengths in units of the crystal reach. Fixed, so the frost is identical every frame.
    private static final float[][] SHARDS = {
            {0.06f, 0.62f, 0.060f}, {0.20f, 1.00f, 0.075f}, {0.34f, 0.55f, 0.050f}, {0.50f, 0.82f, 0.070f},
            {0.66f, 0.50f, 0.050f}, {0.80f, 0.95f, 0.075f}, {0.94f, 0.58f, 0.060f}};

    /** How far the longest shard reaches from its corner. */
    static float crystalReach(int w, int h) {
        return Math.min(w, h) * 0.26f;
    }

    private static void frostCrystals(Canvas canvas, int w, int h, float scale, float intensity) {
        float reach = crystalReach(w, h) * TimedTheme.easeOutCubic(intensity);
        float alpha = FocusBattleTheme.clamp01(intensity);
        for (int corner = 0; corner < 4; corner++) {
            float ox = (corner & 1) == 0 ? 0f : w, oy = corner < 2 ? 0f : h;
            float sx = (corner & 1) == 0 ? 1f : -1f, sy = corner < 2 ? 1f : -1f;
            for (float[] shard : SHARDS) {
                double ang = shard[0] * Math.PI / 2.0;
                float dx = (float) Math.cos(ang) * sx, dy = (float) Math.sin(ang) * sy;
                float len = shard[1] * reach, half = shard[2] * reach;
                float nx = -dy, ny = dx;
                float[] xy = {
                        ox - nx * half, oy - ny * half,
                        ox + dx * len * 0.55f - nx * half * 1.25f, oy + dy * len * 0.55f - ny * half * 1.25f,
                        ox + dx * len, oy + dy * len,
                        ox + dx * len * 0.55f + nx * half * 1.25f, oy + dy * len * 0.55f + ny * half * 1.25f,
                        ox + nx * half, oy + ny * half};
                TimedTheme.fillPolygon(canvas, xy, FocusBattleTheme.fade(TimedTheme.FX_FROST, 0.42f * alpha));
                // A lit facet down one side gives the shard a crystal's edge.
                float[] facet = {xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], ox + dx * len * 0.5f, oy + dy * len * 0.5f};
                TimedTheme.fillPolygon(canvas, facet, FocusBattleTheme.fade(TimedTheme.FX_FROST_EDGE, 0.30f * alpha));
                TimedTheme.strokePolygon(canvas, xy, Math.max(1f, 1.3f * scale), FocusBattleTheme.fade(0xFF3F6E9A, 0.75f * alpha));
            }
        }
    }
}
