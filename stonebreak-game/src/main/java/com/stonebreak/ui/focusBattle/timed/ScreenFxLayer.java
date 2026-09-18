package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MScreenFx;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.focusBattle.BattlePalette;
import io.github.humbleui.skija.Canvas;

/**
 * E14, full-screen effects, drawn as an <b>underlay</b> so the HUD windows stay clean on top. Every
 * effect is a {@link MScreenFx} call; this class only decides <em>how strong</em> each one is from
 * {@link TimedInputState}: a low-HP red vignette (a heartbeat that quickens as HP drops), a frost
 * vignette while the monk is CHILLED, a gold edge aura while Focus is full, a brief flash on
 * critical hits, the slow grey-dark fade of a defeat, and the cinematic letterbox bars.
 *
 * <p>The fight has to stay playable underneath: the library's vignette curve leaves the centre half
 * of the screen untouched and caps every tint, and the three vignettes go through
 * {@link MScreenFx#capStack} so the <em>stack</em> respects the same cap.
 *
 * <p>The one thing painted here by hand is the frost's corner crystals: battle-specific art with no
 * place in a UI library.
 */
public final class ScreenFxLayer {

    private ScreenFxLayer() {}

    static final float LOW_HP_PEAK = 0.44f;
    static final float FROST_PEAK = 0.42f;
    static final float FOCUS_PEAK = 0.30f;
    /** Low HP and frost leave the centre half of the screen alone. */
    static final float CLEAR_RADIUS = 0.5f;
    /** The focus aura hugs the edges: it starts further out than the other two. */
    static final float FOCUS_INNER_RADIUS = 0.82f;
    static final float CRIT_FLASH_PEAK = 0.22f;
    static final float DEFEAT_GREY_PEAK = 0.40f;
    /** Of the library scrim's own strength, so the scene is dimmed, never blacked out. */
    static final float DEFEAT_SCRIM_PEAK = 0.56f;

    static final int LOW_HP_COLOR = MStyle.VITAL_CRIT;
    /** Rime, not water: the frost condition colour pulled most of the way to the UI's warm white. */
    static final int FROST_COLOR = MColor.lerp(BattlePalette.FROST, MStyle.TEXT_PRIMARY, 0.65f);
    static final int FOCUS_COLOR = BattlePalette.FOCUS;
    static final int CRIT_DEALT_COLOR = MStyle.TEXT_PRIMARY;
    static final int CRIT_TAKEN_COLOR = BattlePalette.HIT_FLASH;
    static final int DEFEAT_GREY = MStyle.TEXT_DISABLED;

    private static final int[] VIGNETTE_COLORS = {LOW_HP_COLOR, FROST_COLOR, FOCUS_COLOR};
    private static final float[] VIGNETTE_INNER = {CLEAR_RADIUS, CLEAR_RADIUS, FOCUS_INNER_RADIUS};

    public static void paint(Canvas canvas, int w, int h, float scale, BattleView view, TimedInputState state) {
        if (canvas == null || state == null || w <= 0 || h <= 0) return;
        MScreenFx.stackedVignette(canvas, w, h, VIGNETTE_COLORS, rawPeaks(view, state), VIGNETTE_INNER);
        if (state.frost > 0f) frostCrystals(canvas, w, h, scale, state.frost);

        if (state.critFlashAge >= 0f) {
            float envelope = TimedMotion.flash(state.critFlashAge / TimedInputState.CRIT_FLASH_SECONDS);
            int color = state.critFlashTarget == CombatantId.MONK ? CRIT_TAKEN_COLOR : CRIT_DEALT_COLOR;
            MScreenFx.flash(canvas, w, h, color, CRIT_FLASH_PEAK * envelope);
        }
        if (state.defeatFade > 0f) {
            // A grey wash pulls every colour toward neutral (the desaturation), then the dark closes in.
            float k = TimedMotion.smooth(state.defeatFade);
            MScreenFx.flash(canvas, w, h, DEFEAT_GREY, DEFEAT_GREY_PEAK * k);
            MScreenFx.scrim(canvas, w, h, DEFEAT_SCRIM_PEAK * k * k);
        }
        MScreenFx.letterbox(canvas, w, h, letterboxAmount(state), MScreenFx.LETTERBOX_FRACTION);
    }

    /** Eased bar amount 0..1 handed to {@link MScreenFx#letterbox}. */
    public static float letterboxAmount(TimedInputState state) {
        return TimedMotion.smooth(state.letterbox);
    }

    /**
     * Peak (corner) alpha of {@code {lowHp, frost, focus}} as drawn this frame: each follows its own
     * eased intensity and pulse, then the three are capped together by the library.
     */
    public static float[] vignettePeaks(BattleView view, TimedInputState state) {
        return MScreenFx.capStack(rawPeaks(view, state));
    }

    private static float[] rawPeaks(BattleView view, TimedInputState state) {
        float severity = TimedInputState.lowHpSeverity(view);
        float beat = 0.5f + 0.5f * (float) Math.sin(state.lowHpPhase);
        // Deeper swing the closer to death: barely breathing at the threshold, thumping near zero.
        float lowHp = state.lowHp * LOW_HP_PEAK * (0.62f + (0.18f + 0.2f * severity) * beat);
        float frost = state.frost * FROST_PEAK;
        float focus = state.focusAura * FOCUS_PEAK * (0.75f + 0.25f * (0.5f + 0.5f * (float) Math.sin(state.time * 3.2)));
        return new float[]{lowHp, frost, focus};
    }

    // ─────────────────────────────────────────────── Frost crystals (battle-specific art)

    // Shards fanning out of a corner: {angle as a fraction of the 90° sweep, length, half-width},
    // lengths in units of the crystal reach. Fixed, so the frost is identical every frame.
    private static final float[][] SHARDS = {
            {0.06f, 0.62f, 0.060f}, {0.20f, 1.00f, 0.075f}, {0.34f, 0.55f, 0.050f}, {0.50f, 0.82f, 0.070f},
            {0.66f, 0.50f, 0.050f}, {0.80f, 0.95f, 0.075f}, {0.94f, 0.58f, 0.060f}};

    private static final int SHARD_BODY = MColor.lerp(BattlePalette.FROST, MStyle.TEXT_PRIMARY, 0.8f);
    private static final int SHARD_FACET = MStyle.TEXT_PRIMARY;
    private static final int SHARD_RIM = MColor.lerp(BattlePalette.FROST, MStyle.OUTLINE_DARK, 0.55f);

    /** How far the longest shard reaches from its corner. */
    static float crystalReach(int w, int h) {
        return Math.min(w, h) * 0.26f;
    }

    private static void frostCrystals(Canvas canvas, int w, int h, float scale, float intensity) {
        float reach = crystalReach(w, h) * TimedMotion.settle(intensity);
        float alpha = MColor.clamp01(intensity);
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
                TimedStrokes.fillPolygon(canvas, xy, MColor.withAlpha(SHARD_BODY, 0.5f * alpha));
                // A lit facet down one side gives the shard a crystal's edge.
                float[] facet = {xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], ox + dx * len * 0.5f, oy + dy * len * 0.5f};
                TimedStrokes.fillPolygon(canvas, facet, MColor.withAlpha(SHARD_FACET, 0.35f * alpha));
                TimedStrokes.strokePolygon(canvas, xy, Math.max(1f, 1.3f * scale), MColor.withAlpha(SHARD_RIM, 0.75f * alpha));
            }
        }
    }
}
